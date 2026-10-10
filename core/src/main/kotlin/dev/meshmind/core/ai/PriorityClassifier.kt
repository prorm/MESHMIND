package dev.meshmind.core.ai

import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority

data class ClassificationInput(
    val type: PacketType,
    val text: String = "",
    val sizeBytes: Int = 0,
    /** Set when the user pressed an explicit SOS action. Always CRITICAL; never left to the classifier. */
    val sosAction: Boolean = false,
)

data class Classification(
    val priority: Priority,
    val label: String,
    val reasons: List<String>,
)

/** Pluggable so an on-device model can replace the rules later without touching routing. */
interface PriorityClassifier {
    fun classify(input: ClassificationInput): Classification
}

class RuleBasedPriorityClassifier(
    private val urgentKeywords: Set<String> = setOf(
        "help", "injured", "injury", "medical", "medic", "bleeding", "blood", "unconscious", "fire", "smoke",
        "emergency", "ambulance", "trapped", "stampede", "crush", "heart", "breathing", "fainted", "police",
    ),
    private val coordinationKeywords: Set<String> = setOf("meet", "where", "gate", "lost", "exit", "found", "waiting"),
    private val bulkThresholdBytes: Int = 4 * 1024,
) : PriorityClassifier {

    override fun classify(input: ClassificationInput): Classification {
        if (input.sosAction || input.type == PacketType.SOS) {
            return Classification(Priority.CRITICAL, "SOS", listOf("explicit SOS action"))
        }
        when (input.type) {
            PacketType.ACK -> return Classification(Priority.HIGH, "CONTROL", listOf("delivery ack"))
            PacketType.SUMMARY -> return Classification(Priority.HIGH, "CONTROL", listOf("link summary"))
            PacketType.GATEWAY_BEACON -> return Classification(Priority.NORMAL, "CONTROL", listOf("gateway beacon"))
            PacketType.CHUNK -> return Classification(Priority.LOW, "BULK", listOf("chunk of large transfer"))
            else -> Unit
        }
        val words = input.text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        val urgent = words.intersect(urgentKeywords)
        if (urgent.isNotEmpty()) {
            return Classification(Priority.HIGH, "URGENT", listOf("keywords: ${urgent.sorted().joinToString()}"))
        }
        if (input.sizeBytes > bulkThresholdBytes) {
            return Classification(Priority.LOW, "BULK", listOf("size ${input.sizeBytes}B > $bulkThresholdBytes"))
        }
        val coord = words.intersect(coordinationKeywords)
        if (coord.isNotEmpty()) {
            return Classification(Priority.NORMAL, "COORDINATION", listOf("keywords: ${coord.sorted().joinToString()}"))
        }
        val label = when (input.type) {
            PacketType.INET_REQUEST, PacketType.INET_RESPONSE -> "INTERNET"
            else -> "CHAT"
        }
        return Classification(Priority.NORMAL, label, listOf("default for ${input.type}"))
    }
}
