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
        gs.forEach { g -> items.add((if (g.id == Cloud.active?.id) "✓ " else "    ") + "${g.name}  (코드 ${g.code})") }
        val base = items.size
        items.add("＋ 새 그룹 만들기")
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
                    items[which].startsWith("＋ 새") -> askText("새 그룹 이름", "예: 낚시 모임") { name ->
                        Cloud.createGroup(name) { err -> toast(err ?: "그룹을 만들었어요. 초대 코드: ${Cloud.active?.code}") }
                    }
                    items[which].startsWith("＋ 초대") -> askText("초대 코드 6자리", "예: K7P2QX") { code ->
                        Cloud.joinGroup(code) { err -> toast(err ?: "'${Cloud.active?.name}' 그룹에 가입했어요") }
                    }
                    items[which].startsWith("그룹 초대") -> shareCode()
                    items[which].startsWith("그룹 최근") -> openRecent()
                    items[which].startsWith("로그아웃") -> Cloud.signOut(a)
                }
            }
            .setNegativeButton("닫기", null)
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

    private fun shareCode() {
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

    // ---------------- 그룹 출조 목록 ----------------
    private val dayFmt = SimpleDateFormat("M/d (E) HH:mm", Locale.KOREA)

    fun openRecent() {
        Cloud.recentTrips { list, err ->
            if (list == null) { toast(err ?: "불러오기 실패"); return@recentTrips }
            if (list.isEmpty()) { toast("아직 그룹에 올라온 출조가 없어요"); return@recentTrips }
            val labels = list.map { t ->
                "${dayFmt.format(Date(t.start))} · ${t.ownerName}\n${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)} · 총 ${t.catches.size}"
            }
            AlertDialog.Builder(a, theme)
                .setTitle("${Cloud.active?.name} 최근 출조")
                .setItems(labels.toTypedArray()) { _, i -> showTogether(list[i]) }
                .setNegativeButton("닫기", null)
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
                    t.catches.map { Cloud.RemoteCatch(it.time, it.slot, it.lat, it.lon) }, t.track)
            showTogether(mine, list + if (list.any { it.id == mine.id }) emptyList() else listOf(mine))
        }
    }

    private val personColors = intArrayOf(
        Color.parseColor("#F2C14E"), Color.parseColor("#F07AA0"), Color.parseColor("#7FD4E8"),
        Color.parseColor("#B9A6FF"), Color.parseColor("#5FD39A"), Color.parseColor("#F28C38")
    )

    private fun showTogether(base: Cloud.RemoteTrip, pool: List<Cloud.RemoteTrip>? = null) {
        if (pool == null) {
            Cloud.tripsAround(base.start) { list, err ->
                if (list == null) toast(err ?: "불러오기 실패") else showTogether(base, list)
            }
            return
        }
        val group = Cloud.together(base, pool)
        val tf = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        val layers = group.mapIndexed { i, t ->
            val c = personColors[i % personColors.size]
            MapLayer(c, t.track.map { GeoPoint(it.lat, it.lon) },
                t.catches.filter { it.lat != null && it.lon != null }.map {
                    MapCatch(GeoPoint(it.lat!!, it.lon!!), c, "${t.ownerName} ${tf.format(Date(it.time))} ${t.name(it.slot)}")
                })
        }
        val total = group.sumOf { it.catches.size }
        val sb = StringBuilder("함께한 ${group.size}명 · 합계 ${total}마리\n")
        group.forEachIndexed { i, t ->
            val dot = arrayOf("노랑", "분홍", "하늘", "보라", "초록", "주황")[i % 6]
            sb.append("${i + 1}위 ${t.ownerName}($dot) ${t.catches.size}마리 — ${t.name(1)} ${t.count(1)} · ${t.name(2)} ${t.count(2)}\n")
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
