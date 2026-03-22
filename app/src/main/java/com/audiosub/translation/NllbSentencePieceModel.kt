package com.audiosub.translation

import java.io.File

/**
 * Minimal protobuf reader for sentencepiece .model files.
 *
 * Only decodes the fields needed for BPE encoding/decoding:
 *  - ModelProto.pieces (field 1): repeated SentencePiece messages
 *    - SentencePiece.piece  (field 1, string)
 *    - SentencePiece.score  (field 2, float32)
 *    - SentencePiece.type   (field 3, varint)  1=NORMAL 2=UNKNOWN 3=CONTROL
 */
object NllbSentencePieceModel {

    data class Piece(
        val piece: String,
        val score: Float,
        val type: Int       // 1=NORMAL, 2=UNKNOWN, 3=CONTROL, 4=USER_DEFINED, 6=BYTE
    )

    fun load(file: File): List<Piece> = readPieces(file.readBytes())

    private fun readPieces(data: ByteArray): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var pos = 0
        while (pos < data.size) {
            val (tag, p1) = readVarint(data, pos)
            pos = p1
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            pos = when (wireType) {
                0 -> readVarint(data, pos).second          // varint — skip
                1 -> pos + 8                               // 64-bit fixed — skip
                2 -> {                                     // length-delimited
                    val (len, p) = readVarint(data, pos)
                    val end = p + len.toInt()
                    if (fieldNum == 1) pieces.add(parsePiece(data, p, end))
                    end
                }
                5 -> pos + 4                               // 32-bit fixed — skip
                else -> data.size                          // unknown wire type → bail
            }
        }
        return pieces
    }

    private fun parsePiece(data: ByteArray, start: Int, end: Int): Piece {
        var piece = ""
        var score = 0f
        var type  = 1
        var pos   = start
        while (pos < end) {
            val (tag, p1) = readVarint(data, pos)
            pos = p1
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            pos = when (wireType) {
                0 -> {
                    val (v, p) = readVarint(data, pos)
                    if (fieldNum == 3) type = v.toInt()
                    p
                }
                2 -> {
                    val (len, p) = readVarint(data, pos)
                    val strEnd = p + len.toInt()
                    if (fieldNum == 1) piece = String(data, p, len.toInt(), Charsets.UTF_8)
                    strEnd
                }
                5 -> {  // 32-bit little-endian float
                    val bits =
                        (data[pos].toInt()     and 0xFF)        or
                        ((data[pos+1].toInt()  and 0xFF) shl 8) or
                        ((data[pos+2].toInt()  and 0xFF) shl 16) or
                        ((data[pos+3].toInt()  and 0xFF) shl 24)
                    if (fieldNum == 2) score = java.lang.Float.intBitsToFloat(bits)
                    pos + 4
                }
                else -> end  // unknown — skip to end of this message
            }
        }
        return Piece(piece, score, type)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift  = 0
        var pos    = start
        while (pos < data.size) {
            val b = data[pos++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        return result to pos
    }
}
