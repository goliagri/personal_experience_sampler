/**
 * fdlibm natural log (spec §6.2). On the JVM this is simply StrictMath.log,
 * which is fdlibm's __ieee754_log; the Python side ships a pure-Python port
 * of the same routine. `spec/log_vectors.json` pins bit-identity between the
 * two — mirrors `pes/core/fdlibm_log.py`.
 */
package pes.core

fun fdlibmLog(x: Double): Double = StrictMath.log(x)
