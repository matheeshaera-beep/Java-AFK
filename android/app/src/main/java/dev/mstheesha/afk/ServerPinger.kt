package dev.mstheesha.afk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object ServerPinger {

    fun fetchFavicon(host: String, port: Int): Bitmap? {
        return try {
            val response = statusResponse(host, port) ?: return null
            val base64 = faviconBase64(response) ?: return null
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun statusResponse(host: String, port: Int): String? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())

            val handshake = ByteArrayOutputStream()
            DataOutputStream(handshake).also { w ->
                writeVarInt(w, 0)     // handshake packet id
                writeVarInt(w, 767)   // protocol version
                writeString(w, host)  // server address
                w.writeShort(port)    // server port
                writeVarInt(w, 1)     // next state = status
            }
            writePacket(out, handshake.toByteArray())
            out.flush()

            val request = ByteArrayOutputStream()
            DataOutputStream(request).also { writeVarInt(it, 0) }
            writePacket(out, request.toByteArray())
            out.flush()

            readVarInt(inp)                    // packet length (full)
            readVarInt(inp)                    // packet id (0x00 status response)
            val jsonLen = readVarInt(inp)
            val jsonBytes = ByteArray(jsonLen)
            inp.readFully(jsonBytes)
            String(jsonBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun faviconBase64(json: String): String? {
        val marker = "\"favicon\":\"data:image/png;base64,"
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        val start = idx + marker.length
        val end = json.indexOf('"', start)
        if (end < 0) return null
        return json.substring(start, end)
    }

    private fun writePacket(out: DataOutputStream, payload: ByteArray) {
        writeVarInt(out, payload.size)
        out.write(payload)
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(inp: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = inp.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw Exception("VarInt too big")
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }
}