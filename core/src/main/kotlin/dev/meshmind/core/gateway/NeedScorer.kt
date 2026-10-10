package dev.meshmind.core.gateway

import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.security.Role

/** What a request is about, as far as the scorer can tell. Ordered from most to least urgent. */
enum class NeedCategory(val label: String) {
    EMERGENCY("Emergency"), HEALTH("Health"), SAFETY("Safety & exits"), FAMILY("Find people"),
    TRANSPORT("Transport"), INFO("General info"), LEISURE("Leisure"),
}

/**
 * Everything the gateway knows about one request when it scores it. Only gateway-side facts are trusted;
 * [claimedBattery] is self-reported (the request is signed, so the claim is at least attributable).
 */
data class NeedContext(
    val requester: NodeId,
    val kind: InetKind,
    /** Query, URL or message text. */
    val text: String,
    val claimedBattery: Int,
    val requesterVerified: Boolean,
    val requesterRole: Role,
    /** Ms since the gateway last saw an SOS from this requester, or null if never (gateway's own observation). */
    val sinceRequesterSosMs: Long?,
    /** Urgent-scored requests this requester already made in the current window. */
    val urgentUsed: Int,
    val urgentAllowance: Int,
)

data class NeedScore(
    /** 0..1. */
    val value: Double,
    val category: NeedCategory,
    val reasons: List<String>,
    /** True when the content looked like an emergency (counts against the requester's urgent allowance). */
    val urgentClaim: Boolean,
)

/** Pluggable so a trained on-device model can replace the rules without touching the scheduler. */
fun interface NeedScorer {
    fun score(ctx: NeedContext): NeedScore
}

/**
 * Explainable rules: a base need from what the request is about, then small adjustments from context.
 * It scores the request and the situation, never the person (no demographics, no history beyond fairness).
 */
class RuleBasedNeedScorer : NeedScorer {
    private val categories: List<Triple<NeedCategory, Double, List<String>>> = listOf(
        Triple(NeedCategory.EMERGENCY, 0.95, listOf(
            "help", "emergency", "injured", "bleeding", "unconscious", "trapped", "ambulance", "stampede", "crush",
            "fire", "sos", "dying", "attack", "heart attack", "not breathing", "collapsed", "drowning",
        )),
        Triple(NeedCategory.HEALTH, 0.85, listOf(
            "hospital", "doctor", "medical", "medicine", "clinic", "pharmacy", "first aid", "asthma", "insulin",
            "faint", "fainted", "breathing", "injury", "blood", "medic", "allergy", "pregnant", "chemist",
        )),
        Triple(NeedCategory.SAFETY, 0.8, listOf(
            "exit", "exits", "gate", "gates", "evacuate", "evacuation", "safe", "danger", "police", "crowd",
            "earthquake", "flood", "cyclone", "storm", "alert", "warning", "closed", "blocked", "shelter", "curfew",
        )),
        Triple(NeedCategory.FAMILY, 0.7, listOf(
            "lost", "missing", "child", "kid", "mother", "father", "mom", "dad", "family", "find", "friend",
            "separated", "meet", "reunite",
        )),
        Triple(NeedCategory.TRANSPORT, 0.6, listOf(
            "metro", "train", "bus", "cab", "taxi", "auto", "uber", "ola", "road", "traffic", "station", "airport",
            "flight", "route", "parking", "running", "timings",
        )),
        Triple(NeedCategory.LEISURE, 0.2, listOf(
            "score", "cricket", "match", "movie", "song", "lyrics", "meme", "instagram", "celebrity", "joke",
            "game", "football", "ipl", "trailer", "reel", "netflix",
        )),
    )

    override fun score(ctx: NeedContext): NeedScore {
        val text = " " + ctx.text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        val reasons = ArrayList<String>()
        var category = NeedCategory.INFO
        var value = 0.45
        for ((cat, base, words) in categories) {
            val hits = words.filter { text.contains(" $it ") }
            if (hits.isEmpty()) continue
            // Most urgent matching category wins; leisure only applies when nothing more urgent matched.
            if (category == NeedCategory.INFO || cat.ordinal < category.ordinal) {
                category = cat; value = base
                reasons.removeAll { it.startsWith("about:") }
                reasons += "about: ${cat.label.lowercase()} (${hits.take(3).joinToString()})"
            }
        }
        if (category == NeedCategory.INFO) reasons += "about: general information"
        when (ctx.kind) {
            InetKind.WEBHOOK_POST -> Unit
            InetKind.READ -> { value -= 0.05; reasons += "− full page (larger response)" }
            InetKind.HTTP_GET -> { value -= 0.03; reasons += "− raw web request" }
            InetKind.ASK -> Unit
        }
        val urgent = category == NeedCategory.EMERGENCY
        if (urgent && ctx.urgentUsed >= ctx.urgentAllowance) {
            value = minOf(value, 0.55)
            reasons += "urgent allowance used (${ctx.urgentUsed} in 10 min) → treated as normal"
        }
        ctx.sinceRequesterSosMs?.takeIf { it < 30 * 60_000L }?.let {
            value += 0.15; reasons += "+ this phone sent an SOS ${it / 60_000} min ago"
        }
        if (ctx.requesterVerified && ctx.requesterRole == Role.RESPONDER) { value += 0.25; reasons += "+ verified responder" }
        if (!ctx.requesterVerified) { value -= 0.05; reasons += "− identity not verified" }
        if (ctx.claimedBattery in 0..15) { value += 0.05; reasons += "+ low battery (${ctx.claimedBattery}%, reported)" }
        return NeedScore(value.coerceIn(0.0, 1.0), category, reasons, urgent)
    }
}
