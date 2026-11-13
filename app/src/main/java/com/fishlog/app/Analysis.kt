package com.fishlog.app

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.TimeZone

/**
 * 포인트 지도에서 그린 원 안의 조과 분석
 *  - 어종별: 1시간 평균 · 잘 나온 물때 · 물세기 · 만조/간조 기준 · 시간대(일출 기준) · 수온 · 바람/파고 · 달
 *  - 조건별 막대 (출조 3번 이하인 칸은 '데이터 부족'으로 흐리게)
 */
object Analysis {
    private val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val main = Handler(Looper.getMainLooper())

    /** 조과 한 마리 */
    class Rec(val time: Long, val species: String, val lat: Double, val lon: Double,
              val trip: String, val tripStart: Long, val tripEnd: Long, val wx: String,
              /** true = 내 폰 기록 · false = 전체 게시판 */
              val mine: Boolean,
              /** 게시판 '대략 공개' (자리 범위만) */
              val approx: Boolean,
              /** v3.8.1 그룹 출조 기록에서 온 조과 = 그룹 이름 (아니면 "") · who = 올린 사람 */
              val group: String = "", val who: String = "",
              /** v6.9 (254): 민물 출조 (물때 · 물세기 · 만조간조 계산에서 빼요) */
              val fresh: Boolean = false)

    /** 내 폰 기록 (대략 위치로 잡힌 조과는 빼고) */
    fun mine(cat: String, since: Long): List<Rec> = Store.history.filter { (cat == "" || it.cat == cat) && it.start >= since }.flatMap { t ->
        val end = if (t.end > 0) t.end else t.catches.maxOfOrNull { it.time } ?: t.start
        t.catches.filter { it.lat != null && it.lon != null && it.q != 1 }.map { c ->
            Rec(c.time, t.name(c.slot), c.lat!!, c.lon!!, "m${t.id}", t.start, end, t.wx, true, false, fresh = t.isFresh())
        }
    }

    /** 전체 게시판 (내 글은 내 폰 기록과 겹치니 skipOwner 면 뺌) */
    fun public(list: List<PublicBoard.PTrip>, skipOwner: String?): List<Rec> = list.filter { it.owner != skipOwner }.flatMap { t ->
        t.catches.mapNotNull { c ->
            val la = c.exactLat ?: c.cell?.lat ?: return@mapNotNull null
            val lo = c.exactLon ?: c.cell?.lon ?: return@mapNotNull null
            Rec(c.time, t.name(c.slot), la, lo, "p${t.id}", t.start, if (t.end > 0) t.end else t.start, t.wx, false, !c.hasExact, fresh = t.isFresh())
        }
    }

    /** 그룹 출조 기록 (v3.8.1) · skipOwner = 내 글은 '나만의 포인트'와 겹치니 뺄 때 */
    fun group(list: List<Cloud.RemoteTrip>, gname: String, cat: String, since: Long, skipOwner: String?): List<Rec> =
        list.filter { it.owner != skipOwner && (cat == "" || it.cat == "" || it.cat == cat) && it.start >= since }.flatMap { t ->
            val end = if (t.end > 0) t.end else t.catches.maxOfOrNull { it.time } ?: t.start
            t.catches.filter { it.lat != null && it.lon != null }.map { c ->
                Rec(c.time, t.names.getOrElse(c.slot - 1) { "${c.slot}번" }, c.lat!!, c.lon!!, "g${t.owner}_${t.start}", t.start, end, t.wx, false, false, gname, t.ownerName, t.isFresh())
            }
        }

    fun inCircle(recs: List<Rec>, lat: Double, lon: Double, radiusM: Double): List<Rec> {
        val r = FloatArray(1)
        return recs.filter { Location.distanceBetween(lat, lon, it.lat, it.lon, r); r[0] <= radiusM + if (it.approx) 500.0 else 0.0 }
    }

    // ---------------- 결과 ----------------
    class Bucket(val label: String, val count: Int, val trips: Int, val rate: Double?) {
        val weak: Boolean get() = trips <= 3
    }
    class Cond(val key: String, val title: String, val buckets: List<Bucket>, val rated: Boolean) {
        /** 가장 잘 나온 칸 (데이터 부족 칸은 뒤로) */
        fun best(): Bucket? = buckets.filter { it.count > 0 }.maxWithOrNull(compareBy<Bucket>({ !it.weak }, { it.rate ?: it.count.toDouble() }))
    }
    class Species(val name: String, val count: Int, val trips: Int, val rate: Double, val conds: List<Cond>)
    class Result(val total: Int, val trips: Int, val from: Long, val to: Long, val species: List<Species>, val note: String)

    private class TripInfo(val day: Tide.Day?, val pct: Int?, val snap: Weather.Snap?, val st: Tide.Strength? = null,
                           /** v6.16 (261-2): 조과마다 가장 가까운 관측소 기준 잡은 때 물살 % */
                           val flows: Map<Rec, Int> = emptyMap())

    /** 백그라운드에서 물때·물세기를 받아 계산 (progress = "물때 자료 받는 중 3/9") */
    fun run(ctx: Context, recs: List<Rec>, progress: (String) -> Unit, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val trips = recs.groupBy { it.trip }
            val info = HashMap<String, TripInfo>()
            var i = 0
            trips.entries.take(40).forEach { (k, rs) ->
                i++
                main.post { progress("물때 · 물세기 자료 받는 중 $i/${minOf(trips.size, 40)}") }
                val r0 = rs[0]
                val mid = rs.map { it.time }.sorted()[rs.size / 2]
                // v6.9 (254): 민물 출조는 물때 자료 안 받음
                if (r0.fresh) { info[k] = TripInfo(null, null, if (r0.wx.isNotBlank()) Weather.snapFromJson(r0.wx) else null); return@forEach }
                val d = try { Tide.fetchNow(app, r0.lat, r0.lon, mid) } catch (_: Exception) { null }
                val stg = if (d != null) try { Tide.strengthOf(app, d) } catch (_: Exception) { null } else null
                val pct = stg?.pct
                val snap = if (r0.wx.isNotBlank()) Weather.snapFromJson(r0.wx) else null
                // v6.16 (261-2): 조과마다 가장 가까운 관측소 (출조 기준과 다르면 최대 2곳 더 받기 · 직접 고른 관측소가 있으면 그대로)
                val flows = HashMap<Rec, Int>()
                val mainCode = d?.stationCode
                val near = HashMap<String, Pair<Tide.Day, Tide.Strength>>()
                if (mainCode != null && stg != null && Store.tideStation == null) {
                    val codeOf = rs.associateWith { r -> try { Tide.stationsNear(app, r.lat, r.lon).firstOrNull()?.code } catch (_: Exception) { null } }
                    codeOf.entries.filter { it.value != null && it.value != mainCode }.groupBy { it.value!! }
                        .entries.sortedByDescending { it.value.size }.take(2).forEach { (code, l) ->
                            val r1 = l.first().key
                            val dd = try { Tide.fetchNow(app, r1.lat, r1.lon, mid) } catch (_: Exception) { null }
                            if (dd != null && dd.stationCode == code) {
                                val s2 = try { Tide.strengthOf(app, dd) } catch (_: Exception) { null }
                                if (s2 != null) near[code] = dd to s2
                            }
                        }
                    rs.forEach { r ->
                        val e = codeOf[r]?.let { near[it] }
                        val f = if (e != null) BiteMemo.flowAt(e.first, e.second, r.time) else BiteMemo.flowAt(d, stg, r.time)
                        if (f != null) flows[r] = f
                    }
                }
                info[k] = TripInfo(d, pct ?: Tide.estimatePct(mid), snap, stg, flows)
            }
            val res = compute(recs, info, trips.size > 40)
            main.post { done(res) }
        }.start()
    }

    private fun cal(t: Long) = Calendar.getInstance(KST).apply { timeInMillis = t }

    private fun compute(recs: List<Rec>, info: Map<String, TripInfo>, cut: Boolean): Result {
        val bySp = recs.groupBy { it.species }
        // 출조 시간 (1시간 평균용)
        val tripHours = recs.groupBy { it.trip }.mapValues { (_, rs) ->
            val r = rs[0]; ((r.tripEnd - r.tripStart).coerceAtLeast(15 * 60_000L)) / 3_600_000.0
        }
        val species = bySp.entries.map { (sp, rs) ->
            val tripsOf = rs.map { it.trip }.distinct()
            val hours = tripsOf.sumOf { tripHours[it] ?: 1.0 }.coerceAtLeast(0.25)
            Species(sp, rs.size, tripsOf.size, rs.size / hours, conds(rs, info, tripHours))
        }.sortedByDescending { it.count }
        val ts = recs.map { it.time }
        return Result(recs.size, recs.map { it.trip }.distinct().size, ts.minOrNull() ?: 0L, ts.maxOrNull() ?: 0L, species,
            if (cut) "출조가 많아서 최근 40번만 물때 자료로 계산했어요" else "")
    }

    /** 칸 이름 → 그 칸에 든 조과들 → 막대 */
    private fun bucket(rs: List<Rec>, order: List<String>, tripHours: Map<String, Double>, rated: Boolean, of: (Rec) -> String?): List<Bucket> {
        val g = rs.groupBy { of(it) }.filterKeys { it != null }
        return order.mapNotNull { lab ->
            val l = g[lab] ?: return@mapNotNull null
            val trips = l.map { it.trip }.distinct()
            val rate = if (rated) l.size / trips.sumOf { tripHours[it] ?: 1.0 }.coerceAtLeast(0.25) else null
            Bucket(lab, l.size, trips.size, rate)
        }
    }

    private fun conds(rs: List<Rec>, info: Map<String, TripInfo>, th: Map<String, Double>): List<Cond> {
        val out = ArrayList<Cond>()
        // 물때 (그날 단위 → 1시간 평균)
        val mulOrder = listOf("무시~3물", "4~5물", "6~8물 (사리)", "9~11물", "12물~조금")
        out.add(Cond("mul", "🌊 물때", bucket(rs, mulOrder, th, true) {
            if (it.fresh) return@bucket null
            when (Tide.mulIndex(it.time)) { in 0..3 -> mulOrder[0]; 4, 5 -> mulOrder[1]; in 6..8 -> mulOrder[2]; in 9..11 -> mulOrder[3]; else -> mulOrder[4] }
        }, true))
        // 물세기 %
        val pctOrder = listOf("~40%", "40~55%", "55~70%", "70~85%", "85%~")
        out.add(Cond("pct", "💧 물세기", bucket(rs, pctOrder, th, true) { r ->
            val p = info[r.trip]?.pct ?: return@bucket null
            when { p < 40 -> pctOrder[0]; p < 55 -> pctOrder[1]; p < 70 -> pctOrder[2]; p < 85 -> pctOrder[3]; else -> pctOrder[4] }
        }, true))
        // v6.15 (259): 잡은 때 물살 (그날 물세기 × 그 시각 물흐름 ÷ 그날 최고)
        val flowOrder = listOf("~20%", "20~40%", "40~60%", "60~80%", "80%~")
        out.add(Cond("flow", "💧 잡은 때 물살", bucket(rs, flowOrder, th, false) { r ->
            val inf = info[r.trip] ?: return@bucket null
            val f = inf.flows[r] ?: BiteMemo.flowAt(inf.day, inf.st, r.time) ?: return@bucket null
            when { f < 20 -> flowOrder[0]; f < 40 -> flowOrder[1]; f < 60 -> flowOrder[2]; f < 80 -> flowOrder[3]; else -> flowOrder[4] }
        }, false))
        // 만조 · 간조 기준 (가까운 만조·간조에서 몇 시간 전/후)
        val tideOrder = listOf("만조 3시간 전~", "만조 2~1시간 전", "만조 1시간 전~만조", "만조~1시간 뒤", "만조 1~2시간 뒤",
            "간조 3시간 전~", "간조 2~1시간 전", "간조 1시간 전~간조", "간조~1시간 뒤", "간조 1~2시간 뒤")
        out.add(Cond("tide", "⏱ 만조 · 간조 기준", bucket(rs, tideOrder, th, false) { r ->
            val d = info[r.trip]?.day ?: return@bucket null
            val ex = Tide.extremes(d).minByOrNull { kotlin.math.abs(it.time - r.time) } ?: return@bucket null
            val h = (r.time - ex.time) / 3_600_000.0
            val w = if (ex.high) "만조" else "간조"
            when {
                h < -2 -> "$w 3시간 전~"; h < -1 -> "$w 2~1시간 전"; h < 0 -> "$w 1시간 전~$w"; h < 1 -> "$w~1시간 뒤"; else -> "$w 1~2시간 뒤"
            }
        }, false))
        // 시간대 (일출 · 일몰 기준)
        val sunOrder = listOf("일출 전", "일출 후 0~1시간", "일출 후 1~3시간", "일출 후 3~6시간", "오후 (일몰 3시간 전까지)", "일몰 전 3시간", "일몰 후 · 밤")
        out.add(Cond("sun", "☀ 시간대 (일출 · 일몰 기준)", bucket(rs, sunOrder, th, false) { r ->
            val st = Sun.times(r.time, r.lat, r.lon) ?: return@bucket null
            val h = (r.time - st.rise) / 3_600_000.0
            val toSet = (st.set - r.time) / 3_600_000.0
            when { h < 0 -> sunOrder[0]; toSet < 0 -> sunOrder[6]; h < 1 -> sunOrder[1]; h < 3 -> sunOrder[2]; h < 6 -> sunOrder[3]; toSet > 3 -> sunOrder[4]; else -> sunOrder[5] }
        }, false))
        // 수온 (그 시각 실측 → 예상)
        val sstOrder = listOf("~12℃", "12~15℃", "15~18℃", "18~21℃", "21~24℃", "24℃~")
        out.add(Cond("sst", "🌡 수온", bucket(rs, sstOrder, th, false) { r ->
            val sn = info[r.trip]?.snap ?: return@bucket null
            val hr = r.time - r.time % 3_600_000L
            val v = sn.measured[hr] ?: sn.hours.minByOrNull { kotlin.math.abs(it.time - r.time) }?.takeIf { kotlin.math.abs(it.time - r.time) < 2 * 3_600_000L }?.sst
                ?: return@bucket null
            when { v < 12 -> sstOrder[0]; v < 15 -> sstOrder[1]; v < 18 -> sstOrder[2]; v < 21 -> sstOrder[3]; v < 24 -> sstOrder[4]; else -> sstOrder[5] }
        }, false))
        // 바람 · 파고
        val windOrder = listOf("바람 2m/s 이하", "바람 2~4m/s", "바람 4~7m/s", "바람 7m/s 넘게")
        out.add(Cond("wind", "💨 바람", bucket(rs, windOrder, th, false) { r ->
            val h = info[r.trip]?.snap?.hours?.minByOrNull { kotlin.math.abs(it.time - r.time) }?.takeIf { kotlin.math.abs(it.time - r.time) < 2 * 3_600_000L }
            val w = h?.wind ?: return@bucket null
            when { w <= 2 -> windOrder[0]; w <= 4 -> windOrder[1]; w <= 7 -> windOrder[2]; else -> windOrder[3] }
        }, false))
        val waveOrder = listOf("파고 0.3m 이하", "파고 0.3~0.6m", "파고 0.6~1m", "파고 1m 넘게")
        out.add(Cond("wave", "🌊 파고", bucket(rs, waveOrder, th, false) { r ->
            val h = info[r.trip]?.snap?.hours?.minByOrNull { kotlin.math.abs(it.time - r.time) }?.takeIf { kotlin.math.abs(it.time - r.time) < 2 * 3_600_000L }
            val w = h?.wave ?: return@bucket null
            when { w <= 0.3 -> waveOrder[0]; w <= 0.6 -> waveOrder[1]; w <= 1.0 -> waveOrder[2]; else -> waveOrder[3] }
        }, false))
        // 달 (그날 단위 → 1시간 평균)
        val monOrder = (1..12).map { "${it}월" }
        out.add(Cond("mon", "📅 달", bucket(rs, monOrder, th, true) { "${cal(it.time).get(Calendar.MONTH) + 1}월" }, true))
        return out
    }
}
