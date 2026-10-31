package com.fishlog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** 로그인, 그룹, 함께한 출조 화면 */
class AccountUi(private val a: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { handler.post(it) }
    private val theme = android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun toast(msg: String) = Toast.makeText(a, msg, Toast.LENGTH_LONG).show()

    // ---------------- 구글 로그인 ----------------
    fun signIn(done: () -> Unit) {
        val clientId = a.getString(R.string.default_web_client_id)
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(a).getCredentialAsync(a, request, CancellationSignal(), mainExecutor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val cred = result.credential
                    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val google = GoogleIdTokenCredential.createFrom(cred.data)
                        val fc = GoogleAuthProvider.getCredential(google.idToken, null)
                        FirebaseAuth.getInstance().signInWithCredential(fc)
                            .addOnSuccessListener {
                                Cloud.afterSignIn { err ->
                                    if (err != null) toast(err) else toast("${Cloud.displayName}님 로그인 완료")
                                    Cloud.backupPending()
                                    done()
                                }
                            }
                            .addOnFailureListener { toast("로그인 실패: ${it.message}") }
                    } else {
                        toast("구글 계정 정보를 받지 못했어요")
                    }
                }

                override fun onError(e: GetCredentialException) {
                    toast("로그인 취소/실패: ${e.message ?: e.type}")
                }
            })
    }

    // ---------------- 그룹 ----------------
    fun openGroups() {
        if (Cloud.user == null) { signIn { openGroups() }; return }
        val gs = Cloud.groups
        val items = mutableListOf<String>()
        gs.forEach { g -> items.add((if (g.id == Cloud.active?.id) "✓ " else "    ") + "${g.title}  · 초대 코드 ${g.code}") }
        val base = items.size
        items.add(if (Cloud.can("group")) "＋ 새 그룹 만들기" else "＋ 새 그룹 만들기 (운영자 허용 필요)")
        items.add("＋ 초대 코드로 가입")
        if (Cloud.active != null) {
            items.add("그룹 초대 코드 보내기")
            items.add("그룹 최근 출조 보기")
        }
        items.add("로그아웃 (${Cloud.displayName})")

        AlertDialog.Builder(a, theme)
            .setTitle(if (gs.isEmpty()) "그룹이 아직 없어요" else "내 그룹 (눌러서 선택)")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < base -> { Cloud.setActive(gs[which].id); toast("'${gs[which].name}' 선택") }
                    items[which].startsWith("＋ 새") && !Cloud.can("group") ->
                        AlertDialog.Builder(a, theme).setTitle("그룹 만들기는 허용된 사람만 할 수 있어요")
                            .setMessage("운영자에게 '그룹 만들기' 권한을 요청해 주세요.\n메인 맨 위 ✉ 1:1 문의로 보낼 수 있어요.\n\n초대 코드가 있으면 '초대 코드로 가입'은 누구나 할 수 있어요.")
                            .setPositiveButton("확인", null).show()
                    items[which].startsWith("＋ 새") -> askText("새 그룹 이름", "예: 낚시 모임") { name ->
                        askTypes("어떤 출조 그룹인가요? (여러 개 선택 가능)", emptyList()) { types ->
                            Cloud.createGroup(name, types) { err -> toast(err ?: "그룹을 만들었어요. 초대 코드: ${Cloud.active?.code}") }
                        }
                    }
                    items[which].startsWith("＋ 초대") -> askText("초대 코드 6자리", "예: K7P2QX") { code ->
                        Cloud.joinGroup(code) { err -> toast(err ?: "'${Cloud.active?.name}' 그룹에 가입했어요") }
                    }
                    items[which].startsWith("그룹 초대") -> shareCode()
                    items[which].startsWith("그룹 최근") -> openRecent()
                    items[which].startsWith("로그아웃") -> {
                        Cloud.signOut(a)
                        // 로그아웃하면 로그인 화면으로
                        a.startActivity(Intent(a, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 선상 · 해루질 · 워킹 여러 개 고르기 */
    fun askTypes(title: String, current: List<String>, ok: (List<String>) -> Unit) {
        val cats = Store.CATS
        val checked = BooleanArray(cats.size) { cats[it] in current }
        AlertDialog.Builder(a, theme)
            .setTitle(title)
            .setMultiChoiceItems(cats.map { Store.catLabel(it) }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("확인") { _, _ ->
                val picked = cats.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) toast("하나 이상 골라 주세요") else ok(picked)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askText(title: String, hint: String, ok: (String) -> Unit) {
        val input = EditText(a).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(a, theme)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("확인") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("취소", null)
            .show()
    }

    fun shareCode() {
        val g = Cloud.active ?: return
        val text = "조과기록 앱 '${g.name}' 그룹 초대 코드: ${g.code}\n앱 → 그룹 → 초대 코드로 가입에 입력하세요"
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("초대 코드", g.code))
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "초대 코드 보내기"))
    }

    // ---------------- 업로드 ----------------
    fun upload(t: Store.Trip, quiet: Boolean = false) {
        if (Cloud.user == null || Cloud.active == null) {
            if (!quiet) toast("로그인하고 그룹을 선택하면 올릴 수 있어요")
            return
        }
        if (!quiet) toast("'${Cloud.active?.name}' 그룹에 올리는 중…")
        Cloud.uploadTrip(t) { err -> if (err != null) toast("올리기 실패: $err") else if (!quiet) toast("그룹에 올렸어요") }
    }

    /** 고른 그룹들에 올리기 (기본: 가입한 그룹 전체) */
    fun uploadAll(t: Store.Trip, only: List<Cloud.Group>? = null) {
        val gs = only ?: Cloud.groups
        if (Cloud.user == null || gs.isEmpty()) { toast("로그인하고 그룹에 가입하면 올릴 수 있어요"); return }
        toast("그룹 ${gs.size}곳에 올리는 중…")
        var left = gs.size
        val fails = mutableListOf<String>()
        gs.forEach { g ->
            Cloud.uploadTrip(t, g) { err ->
                if (err != null) fails.add(g.name)
                left--
                if (left == 0) toast(if (fails.isEmpty()) "그룹 ${gs.size}곳에 올렸어요" else "올리기 실패: ${fails.joinToString(", ")}")
            }
        }
    }

    // ---------------- 그룹 출조 목록 ----------------
    private val dayFmt = SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA)

    fun openRecent() {
        a.startActivity(Intent(a, GroupActivity::class.java))
    }

    /** 친구가 올린 사진 (작은 사진) */
    fun showPhotos(t: Cloud.RemoteTrip) {
        toast("사진 불러오는 중…")
        Cloud.loadPhotos(t) { list, err ->
            if (list == null) { toast(err ?: "불러오기 실패"); return@loadPhotos }
            if (list.isEmpty()) { toast("이 출조엔 올라온 사진이 없어요"); return@loadPhotos }
            val tf = SimpleDateFormat("HH:mm", Locale.KOREA)
            val col = android.widget.LinearLayout(a).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8))
            }
            list.forEach { p ->
                val c = t.catches.firstOrNull { it.time == p.catchTime }
                val caption = "${t.ownerName} · " + (if (p.time > 0) "촬영 ${tf.format(Date(p.time))}" else "") +
                    (if (c != null) " · ${t.name(c.slot)}" else "")
                col.addView(android.widget.ImageView(a).apply {
                    adjustViewBounds = true
                    setImageBitmap(p.bitmap)
                    setOnClickListener {
                        // 작은 사진을 먼저 보여주고, 큰 사진이 오면 바꿔 끼움
                        val swap = ZoomImageView.show(a, p.bitmap, "$caption · 큰 사진 받는 중…")
                        Cloud.loadBigPhoto(t.id, p.id) { big -> if (big != null) swap(big) }
                    }
                }, Ui.lp(a, Ui.MATCH, Ui.WRAP, top = 8))
                col.addView(Ui.text(a, 12f, Ui.SUB, false, caption.removePrefix("${t.ownerName} · ") + " · 눌러서 크게"))
            }
            AlertDialog.Builder(a, theme)
                .setTitle("${t.ownerName}님 사진 ${list.size}장")
                .setView(android.widget.ScrollView(a).apply { addView(col) })
                .setPositiveButton("닫기", null)
                .show()
        }
    }

    /** 내 출조 기록에서 "함께한 출조" 보기 */
    fun openTogetherFor(t: Store.Trip) {
        val myId = Cloud.uid
        if (myId == null || Cloud.active == null) { toast("로그인하고 그룹을 선택해 주세요"); return }
        Cloud.tripsAround(t.start) { list, err ->
            if (list == null) { toast(err ?: "불러오기 실패"); return@tripsAround }
            val mine = list.firstOrNull { it.id == "${myId}_${t.id}" }
                ?: Cloud.RemoteTrip("${myId}_${t.id}", myId, Cloud.displayName, t.start, t.end, t.names.toList(),
                    t.catches.map { Cloud.RemoteCatch(it.time, it.slot, it.lat, it.lon) }, t.track, t.boat, t.port)
            showTogether(mine, list + if (list.any { it.id == mine.id }) emptyList() else listOf(mine))
        }
    }

    private val personColors = intArrayOf(
        Color.parseColor("#F2C14E"), Color.parseColor("#F07AA0"), Color.parseColor("#7FD4E8"),
        Color.parseColor("#B9A6FF"), Color.parseColor("#5FD39A"), Color.parseColor("#F28C38")
    )

    fun showTogether(base: Cloud.RemoteTrip, pool: List<Cloud.RemoteTrip>? = null) {
        if (pool == null) {
            Cloud.tripsAround(base.start) { list, err ->
                if (list == null) toast(err ?: "불러오기 실패") else showTogether(base, list)
            }
            return
        }
        // 경로는 지도를 열 때만 받음
        Cloud.loadTracks(Cloud.together(base, pool)) { group -> showTogetherMap(base, group) }
    }

    private fun showTogetherMap(base: Cloud.RemoteTrip, group: List<Cloud.RemoteTrip>) {
        val tf = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        val layers = group.mapIndexed { i, t ->
            val c = personColors[i % personColors.size]
            MapLayer(c, t.track.map { GeoPoint(it.lat, it.lon) },
                t.catches.filter { it.lat != null && it.lon != null }.map {
                    MapCatch(GeoPoint(it.lat!!, it.lon!!), c, "${t.ownerName} ${tf.format(Date(it.time))} ${t.name(it.slot)}", "${t.ownerName} ${t.name(it.slot)}",
                        it.time, t.ownerName, t.name(it.slot), MapDialog.paidMark(t.cat, t.paid))
                })
        }
        val total = group.sumOf { it.catches.size }
        val sb = StringBuilder("함께한 ${group.size}명 · 합계 ${total}마리\n")
        group.forEachIndexed { i, t ->
            val dot = arrayOf("노랑", "분홍", "하늘", "보라", "초록", "주황")[i % 6]
            sb.append("${i + 1}위 ${t.ownerName}($dot) ${t.catches.size}마리 — ${t.line()}\n")
        }
        // 가장 입질이 몰린 1시간
        val times = group.flatMap { t -> t.catches.map { it.time } }.sorted()
        if (times.size >= 3) {
            var best = 0; var bestStart = times[0]; var j = 0
            for (i in times.indices) {
                while (times[i] - times[j] > 3600_000L) j++
                if (i - j + 1 > best) { best = i - j + 1; bestStart = times[j] }
            }
            val hf = SimpleDateFormat("HH:mm", Locale.KOREA)
            sb.append("베스트 타임 ${hf.format(Date(bestStart))}부터 1시간 · ${best}마리")
        }
        MapDialog.show(a, "${dayFmt.format(Date(base.start))} 함께한 출조", layers, sb.toString().trimEnd())
    }
}
