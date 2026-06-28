package net.guacamaya.proto

/**
 * CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection, no final XOR).
 *
 * Used as a cheap reject-before-sig-verify check on the 22-byte payload. NOT a
 * security primitive — only an optimization to drop junk frames before the
 * expensive Ed25519 verify.
 */
object Crc16 {

    fun ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            crc = crc and 0xFFFF
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
