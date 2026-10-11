package com.shoosman.bookapp.sync

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import com.shoosman.bookapp.net.Http
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/** Ошибка ответа GitHub с кодом. */
class GitHubException(val code: Int, message: String) : IOException(message)

/** Вход в GitHub по коду (device flow) — без паролей и ручных токенов. */
object GitHubAuth {
    /** Client ID приложения «Chtets Sync shoosman» на GitHub. Он публичный. */
    const val CLIENT_ID = "Iv23li4CIZNvJdUYvgfA"

    class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Int, val expiresIn: Int)

    sealed interface Poll {
        class Token(val token: String) : Poll
        data object Pending : Poll
        data object SlowDown : Poll
        class Failed(val message: String) : Poll
    }

    fun start(): DeviceCode {
        val json = postForm("https://github.com/login/device/code", mapOf("client_id" to CLIENT_ID))
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            interval = json.optInt("interval", 5),
            expiresIn = json.optInt("expires_in", 900),
        )
    }

    fun poll(code: DeviceCode): Poll {
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to CLIENT_ID,
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.optString("access_token").takeIf { it.isNotBlank() }?.let { return Poll.Token(it) }
        return when (json.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "expired_token" -> Poll.Failed("Код устарел. Начните вход заново.")
            "access_denied" -> Poll.Failed("Вход отменён на GitHub.")
            else -> Poll.Failed(json.optString("error_description", "Не удалось войти"))
        }
    }

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", "Chtets")
            .build()
        return withRetry {
            Http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                runCatching { JSONObject(text) }.getOrElse { throw GitHubException(resp.code, "GitHub ответил: ${resp.code}") }
            }
        }
    }
}

/** Повтор при сетевых сбоях (не при ответах GitHub с ошибкой). */
internal fun <T> withRetry(times: Int = 3, block: () -> T): T {
    var last: IOException? = null
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: GitHubException) {
            throw e
        } catch (e: IOException) {
            last = e
            Thread.sleep(1500L * (attempt + 1))
        }
    }
    throw last ?: IOException("Сеть недоступна")
}

/** Минимальный клиент GitHub REST API для одного репозитория. */
class GitHubApi(private val token: String, val repo: String) {

    fun get(path: String): Any = request("GET", path, null)
    fun post(path: String, body: JSONObject): Any = request("POST", path, body)
    fun patch(path: String, body: JSONObject): Any = request("PATCH", path, body)

    fun user(): JSONObject = get("/user") as JSONObject
    fun repoInfo(): JSONObject = get("/repos/$repo") as JSONObject

    fun branchHead(branch: String): String =
        ((get("/repos/$repo/git/ref/heads/$branch") as JSONObject).getJSONObject("object")).getString("sha")

    fun commitTree(commitSha: String): String =
        (get("/repos/$repo/git/commits/$commitSha") as JSONObject).getJSONObject("tree").getString("sha")

    /** Все файлы репозитория: путь → sha. */
    fun tree(treeSha: String): Map<String, String> {
        val json = get("/repos/$repo/git/trees/$treeSha?recursive=1") as JSONObject
        val arr = json.getJSONArray("tree")
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optString("type") == "blob") out[e.getString("path")] = e.getString("sha")
        }
        return out
    }

    fun blob(sha: String): ByteArray {
        val json = get("/repos/$repo/git/blobs/$sha") as JSONObject
        return Base64.decode(json.getString("content").replace("\n", ""), Base64.DEFAULT)
    }

    fun createBlob(bytes: ByteArray): String {
        val body = JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")
        return (post("/repos/$repo/git/blobs", body) as JSONObject).getString("sha")
    }

    /** Новый коммит поверх [parent]: [changes] — путь → sha блоба (null — удалить файл). */
    fun commit(parent: String, baseTree: String, changes: Map<String, String?>, message: String): String {
        val entries = JSONArray()
        for ((path, sha) in changes) {
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("sha", sha ?: JSONObject.NULL),
            )
        }
        val tree = (post("/repos/$repo/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries)) as JSONObject)
            .getString("sha")
        val commit = post(
            "/repos/$repo/git/commits",
            JSONObject().put("message", message).put("tree", tree).put("parents", JSONArray().put(parent)),
        ) as JSONObject
        return commit.getString("sha")
    }

    /** Передвинуть ветку. Если кто-то успел закоммитить раньше — GitHubException(422). */
    fun moveBranch(branch: String, sha: String) {
        patch("/repos/$repo/git/refs/heads/$branch", JSONObject().put("sha", sha).put("force", false))
    }

    private fun request(method: String, path: String, body: JSONObject?): Any {
        val reqBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.github.com$path")
            .method(method, reqBody)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Chtets")
            .build()
        return withRetry {
            Http.client.newCall(req).execute().use { resp ->
                val code = resp.code
                val text = resp.body?.string().orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                    throw GitHubException(code, when (code) {
                        401 -> "Вход в GitHub больше не действует. Подключите GitHub заново."
                        403 -> "GitHub отказал в доступе: $msg"
                        404 -> "Не найдено на GitHub: $msg"
                        else -> "GitHub ответил $code: $msg"
                    })
                }
                if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text.ifBlank { "{}" })
            }
        }
    }

    companion object {
        /** SHA блоба так, как его считает git: sha1("blob <размер>\0" + содержимое). */
        fun gitSha(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${bytes.size}\u0000".toByteArray())
            md.update(bytes)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
