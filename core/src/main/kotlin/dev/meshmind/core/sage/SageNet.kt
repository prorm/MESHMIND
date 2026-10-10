package dev.meshmind.core.sage

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * SAGE-Net v1 forward pass in plain Kotlin (15,124 parameters, ~30k multiply-adds per row): no native runtime, the
 * same code on JVM tests and phones. Twin of research/sage/sage/v1/model.py (SageNet.forward); parity with the ONNX
 * export is checked against golden vectors (SageGoldenTest).
 *
 * Output order (as the shipped ONNX graph): u_self, u_peer, p_reach_self, p_reach_peer, gain_replicate, gain_forward.
 * The ONNX export applies a sigmoid to the reach heads (the torch module returns logits); this matches the ONNX.
 */
class SageNet private constructor(private val t: Map<String, Tensor>, val hidden: Int, val embed: Int) {

    class Tensor(val shape: IntArray, val data: DoubleArray) {
        operator fun get(r: Int, c: Int) = data[r * shape[1] + c]
    }

    private val mean = t.getValue("mean").data
    private val scale = t.getValue("scale").data
    private val lnW = t.getValue("encoder.0.weight").data
    private val lnB = t.getValue("encoder.0.bias").data
    private val enc1 = t.getValue("encoder.1.weight"); private val enc1b = t.getValue("encoder.1.bias").data
    private val enc3 = t.getValue("encoder.3.weight"); private val enc3b = t.getValue("encoder.3.bias").data
    private val value = t.getValue("value.weight"); private val valueB = t.getValue("value.bias").data
    private val gain0 = t.getValue("gain.0.weight"); private val gain0b = t.getValue("gain.0.bias").data
    private val gain2 = t.getValue("gain.2.weight"); private val gain2b = t.getValue("gain.2.bias").data

    init {
        require(mean.size == SageFeatures.COUNT && scale.size == SageFeatures.COUNT) { "feature width mismatch" }
        require(enc1.shape[1] == SageFeatures.N_PACKET + SageFeatures.N_NODE) { "encoder input mismatch" }
    }

    /** Scores one feature row (length 59). */
    fun predict(x: FloatArray): FloatArray {
        require(x.size == SageFeatures.COUNT)
        val z = DoubleArray(x.size) { (x[it] - mean[it]) / scale[it] }
        val p = SageFeatures.N_PACKET; val n = SageFeatures.N_NODE
        val hs = encode(DoubleArray(p + n) { if (it < p) z[it] else z[p + (it - p)] })
        val hp = encode(DoubleArray(p + n) { if (it < p) z[it] else z[p + n + (it - p)] })
        val vs = linear(value, valueB, hs)
        val vp = linear(value, valueB, hp)
        val pairStart = p + 2 * n
        val g0 = DoubleArray(3 * embed + SageFeatures.N_PAIR) { i ->
            when {
                i < embed -> hs[i]
                i < 2 * embed -> hp[i - embed]
                i < 3 * embed -> hs[i - 2 * embed] * hp[i - 2 * embed]
                else -> z[pairStart + i - 3 * embed]
            }
        }
        val g = linear(gain2, gain2b, gelu(linear(gain0, gain0b, g0)))
        return floatArrayOf(
            sigmoid(vs[0]).toFloat(), sigmoid(vp[0]).toFloat(), sigmoid(vs[1]).toFloat(), sigmoid(vp[1]).toFloat(),
            sigmoid(g[0]).toFloat(), tanh(g[1]).toFloat(),
        )
    }

    private fun encode(x: DoubleArray): DoubleArray {
        val mu = x.average()
        val v = x.sumOf { (it - mu) * (it - mu) } / x.size
        val d = sqrt(v + 1e-5)
        val ln = DoubleArray(x.size) { (x[it] - mu) / d * lnW[it] + lnB[it] }
        return gelu(linear(enc3, enc3b, gelu(linear(enc1, enc1b, ln))))
    }

    private fun linear(w: Tensor, b: DoubleArray, x: DoubleArray): DoubleArray {
        val rows = w.shape[0]; val cols = w.shape[1]
        require(x.size == cols)
        return DoubleArray(rows) { r ->
            var s = b[r]
            val off = r * cols
            for (c in 0 until cols) s += w.data[off + c] * x[c]
            s
        }
    }

    companion object {
        const val U_SELF = 0
        const val U_PEER = 1
        const val GAIN_REPLICATE = 4
        const val GAIN_FORWARD = 5
        const val RESOURCE = "/sage/sage_net_weights.json"

        /** Loads the bundled weights; throws if missing or the schema does not match [SageFeatures.NAMES]. */
        fun loadBundled(): SageNet {
            val text = SageNet::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("SAGE weights resource $RESOURCE missing")
            return fromJson(text)
        }

        @Suppress("UNCHECKED_CAST")
        fun fromJson(text: String): SageNet {
            val root = MiniJson.parse(text) as Map<String, Any?>
            require((root["feature_names"] as List<*>) == SageFeatures.NAMES) { "SAGE feature schema mismatch" }
            val tensors = (root["tensors"] as Map<String, Any?>).mapValues { (_, v) ->
                val m = v as Map<String, Any?>
                val shape = (m["shape"] as List<*>).map { (it as Double).toInt() }.toIntArray()
                val data = (m["data"] as List<*>).map { it as Double }.toDoubleArray()
                require(data.size == shape.fold(1) { a, b -> a * b }) { "tensor size mismatch" }
                Tensor(if (shape.size == 1) intArrayOf(shape[0], 1) else shape, data)
            }
            return SageNet(tensors, (root["hidden"] as Double).toInt(), (root["embed"] as Double).toInt())
        }

        private fun sigmoid(v: Double) = 1.0 / (1.0 + exp(-v))

        private fun gelu(x: DoubleArray) = DoubleArray(x.size) { 0.5 * x[it] * (1.0 + erf(x[it] / sqrt(2.0))) }

        /**
         * erf for the exact GELU used in training. |x| < 3: Maclaurin series (error < 1e-11). Beyond: Abramowitz &
         * Stegun 7.1.26 (error < 1.5e-7, where erf is already within 2e-5 of ±1).
         */
        internal fun erf(x: Double): Double {
            val sign = if (x < 0) -1.0 else 1.0
            val a = abs(x)
            if (a < 3.0) return sign * series(a)
            if (a > 6.0) return sign
            val tt = 1.0 / (1.0 + 0.3275911 * a)
            return sign * (1.0 - (((((1.061405429 * tt - 1.453152027) * tt) + 1.421413741) * tt - 0.284496736) * tt + 0.254829592) * tt * exp(-a * a))
        }

        /** erf(a) = 2/√π Σ (-1)^n a^(2n+1) / (n! (2n+1)). */
        private fun series(a: Double): Double {
            var sum = 0.0
            var term = a
            var n = 0
            while (n < 200) {
                val add = term / (2 * n + 1)
                sum += add
                if (abs(add) < 1e-17) break
                n++
                term *= -a * a / n
            }
            return 2.0 / sqrt(Math.PI) * sum
        }
    }
}
