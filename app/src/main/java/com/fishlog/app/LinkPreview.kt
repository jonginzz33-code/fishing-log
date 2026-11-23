package com.fishlog.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * v6.64 (351): 🔗 채팅 링크 — 글 안 주소를 누르면 인터넷 창 · 말풍선 아래 미리보기 카드 (A안)
 *  - 미리보기는 '보내는 사람 폰'이 보내기 전에 만들어 글에 같이 저장 (lp {u,t,d,img})
 *    → 받는 사람 폰은 그 사이트에 접속하지 않음 (전체 채팅도 안전)
 *  - 예전 글 · 1:1 문의처럼 lp 가 없는 글은 그룹 방 · 문의에서만 내 폰이 받아 봄 (메모리 기억)
 *  - 사진은 작게 줄인 JPEG base64 (≤ 30KB)
 */
object LinkPreview {
    class Prev(val u: String, val t: String, val d: String, val img: String) {
        fun toMap(): Map<String, Any> = mapOf("u" to u, "t" to t, "d" to d, "img" to img)
        val domain get() = try { Uri.parse(u).host?.removePrefix("www.") ?: u } catch (_: Exception) { u }
    }

    private val RE = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"'()\[\]{}]+)""")
    private val main = Handler(Looper.getMainLooper())

    fun fromMap(m: Any?): Prev? {
        val mm = m as? Map<*, *> ?: return null
        val u = mm["u"] as? String ?: return null
        return Prev(u, (mm["t"] as? String).orEmpty(), (mm["d"] as? String).orEmpty(), (mm["img"] as? String).orEmpty())
    }

    /** 글 안 링크들 (끝의 . , ! ? 는 뺌) */
    fun urls(text: String): List<IntRange> = RE.findAll(text).map { r ->
        var end = r.range.last
        while (end > r.range.first && text[end] in ".,!?;:~…") end--
        r.range.first..end
    }.toList()

    fun firstUrl(text: String): String? = urls(text).firstOrNull()?.let { norm(text.substring(it.first, it.last + 1)) }

    /** http 없으면 https:// 붙임 · http/https 만 */
    fun norm(u: String): String {
        val s = u.trim()
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "https://$s"
    }

    fun open(c: Context, url: String) {
        try { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(norm(url))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(c, "이 주소를 열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show() }
    }

    /** 글자에 하늘색 밑줄 링크 (누르면 인터넷 창) — TextView 에 movementMethod 도 걸어 줌 */
    fun linkify(tv: TextView, text: String, linkColor: Int) {
        val rs = urls(text)
        if (rs.isEmpty()) { tv.text = text; return }
        val sb = SpannableStringBuilder(text)
        rs.forEach { r ->
            val u = text.substring(r.first, r.last + 1)
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(w: View) { open(w.context, u) }
                override fun updateDrawState(ds: TextPaint) { ds.color = linkColor; ds.isUnderlineText = true }
            }, r.first, r.last + 1, 0)
        }
        tv.text = sb
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        tv.highlightColor = Color.TRANSPARENT
    }

    // ---------------- 미리보기 받기 ----------------
    private val mem = HashMap<String, Prev>()
    private val failed = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(Prev?) -> Unit>>()

    /** 내 폰이 받아 보기 (메모리 기억 · 같은 주소는 한 번만) — 결과는 메인 스레드 */
    fun load(url: String, cb: (Prev?) -> Unit) {
        val u = norm(url)
        mem[u]?.let { cb(it); return }
        if (u in failed) { cb(null); return }
        waiting[u]?.let { it.add(cb); return }
        waiting[u] = mutableListOf(cb)
        Thread {
            val p = try { fetch(u) } catch (_: Throwable) { null }
            main.post {
                if (p != null) mem[u] = p else failed.add(u)
                waiting.remove(u)?.forEach { it(p) }
            }
        }.start()
    }

    /** 페이지 앞부분(64KB)에서 og 태그 · <title> 읽기 + 사진 작게 (막히는 일 · 뒤 스레드에서) */
    fun fetch(url: String): Prev? {
        val u = norm(url)
        val c = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000; readTimeout = 3000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html")
        }
        try {
            if (c.responseCode !in 200..299) return null
            val type = c.contentType.orEmpty()
            if (!type.contains("html", true)) return null
            val buf = ByteArray(65536)
            var n = 0
            c.inputStream.use { ins ->
                while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
            }
            val headCs = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
            val ascii = String(buf, 0, n, Charsets.ISO_8859_1)
            val metaCs = Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE).find(ascii)?.groupValues?.get(1)
            val cs = try { charset(headCs ?: metaCs ?: "UTF-8") } catch (_: Exception) { Charsets.UTF_8 }
            val html = String(buf, 0, n, cs)
            fun meta(vararg keys: String): String {
                for (k in keys) {
                    val a = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']$k["'][^>]*content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
                        ?: Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']$k["']""", RegexOption.IGNORE_CASE).find(html)
                    val v = a?.groupValues?.get(1)?.trim()
                    if (!v.isNullOrBlank()) return unescape(v)
                }
                return ""
            }
            val title = meta("og:title", "twitter:title").ifBlank {
                Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()?.let { unescape(it) }.orEmpty()
            }.take(80)
            val desc = meta("og:description", "twitter:description", "description").take(120)
            var img = meta("og:image", "twitter:image")
            if (img.isNotBlank()) img = try { URL(c.url, img).toString() } catch (_: Exception) { "" }
            val thumb = if (img.startsWith("http")) thumbOf(img) else ""
            if (title.isBlank() && desc.isBlank() && thumb.isBlank()) return null
            return Prev(u, title, desc, thumb)
        } finally { c.disconnect() }
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")

    /** 사진 받아 가로 480 정도로 줄여 base64 (≤ 30KB) */
    private fun thumbOf(src: String): String = try {
        val c = (URL(src).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500; readTimeout = 2500; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        }
        val bytes = try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { ins ->
                val out = ByteArrayOutputStream(); val b = ByteArray(16384); var tot = 0
                while (true) { val r = ins.read(b); if (r <= 0) break; out.write(b, 0, r); tot += r; if (tot > 3_000_000) break }
                if (tot > 3_000_000) null else out.toByteArray()
            }
        } finally { c.disconnect() }
        if (bytes == null) "" else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var ss = 1
            while (o.outWidth / (ss * 2) >= 480) ss *= 2
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
            if (bm == null) "" else {
                val w = minOf(480, bm.width)
                val sc = android.graphics.Bitmap.createScaledBitmap(bm, w, (bm.height * w.toFloat() / bm.width).toInt().coerceAtLeast(1), true)
                var q = 70; var out: ByteArray
                do { val bo = ByteArrayOutputStream(); sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bo); out = bo.toByteArray(); q -= 15 } while (out.size > 30_000 && q > 20)
                if (out.size > 30_000) "" else Base64.encodeToString(out, Base64.NO_WRAP)
            }
        }
    } catch (_: Throwable) { "" }

    // ---------------- 카드 (A안) ----------------
    fun card(a: Activity, p: Prev, onLong: (() -> Unit)? = null): View {
        fun dp(v: Int) = Ui.dp(a, v)
        val w = (Ui.contentW(a) * 0.66).toInt()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bg(a, Color.parseColor("#12283A"), 14, Color.parseColor("#3A6A88"))
            clipToOutline = true
            isClickable = true
            setOnClickListener { open(a, p.u) }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val bm = if (p.img.isBlank()) null else try {
            val b = Base64.decode(p.img, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (_: Exception) { null }
        if (bm != null) box.addView(FrameLayout(a).apply {
            addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bm) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        val inner = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(9)) }
        inner.addView(Ui.text(a, 13.5f, Ui.TEXT, true, p.t.ifBlank { p.domain }).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = if (p.d.isNotBlank()) p.d else if (p.t.isBlank()) "제목을 못 불러왔어요" else ""
        if (sub.isNotBlank()) inner.addView(Ui.text(a, 11.5f, Color.parseColor("#9FB4C2"), false, sub).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        val foot = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }
        foot.addView(Ui.text(a, 11f, Color.parseColor("#7FA3B8"), false, "🌐 ${p.domain}").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(TextView(a).apply {
            text = "열기 ›"; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#06303A")); setPadding(dp(10), dp(3), dp(10), dp(3))
            background = Ui.bg(a, Color.parseColor("#5BC8E8"), 99)
        })
        inner.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        box.addView(inner)
        box.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        return box
    }

    /** 링크 꾹 메뉴 항목 (열기 · 주소 복사 · 내 사이트에 담기) */
    fun menuItems(a: Activity, text: String, title: String?): List<Pair<String, () -> Unit>> {
        val u = firstUrl(text) ?: return emptyList()
        return listOf(
            "🌐 인터넷 창으로 열기" to { open(a, u) },
            "📋 주소 복사" to {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("link", u))
                Toast.makeText(a, "주소를 복사했어요", Toast.LENGTH_SHORT).show()
            },
            "🔖 내 사이트에 담기 (자주 가는 사이트)" to {
                val name = title?.takeIf { it.isNotBlank() }?.take(30) ?: (Uri.parse(u).host?.removePrefix("www.") ?: u)
                val ok = Boats.addMine(a, Boats.Site(name, u))
                Toast.makeText(a, if (ok) "'자주 가는 사이트'에 담았어요" else "이미 담긴 주소예요", Toast.LENGTH_SHORT).show()
            })
    }
}
