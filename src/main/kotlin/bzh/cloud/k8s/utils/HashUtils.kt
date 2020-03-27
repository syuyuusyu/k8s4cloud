import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest


private  val HASH = "SHA256"

fun sha256(data: ByteArray): String {
    val md = MessageDigest.getInstance(HASH);
    md.update(data)
    return array2str(md.digest())
}

fun sha256(filePath: Path): String {
    val md = MessageDigest.getInstance(HASH);
    val buffer = ByteArray(1024 * 100)
    BufferedInputStream(Files.newInputStream(filePath)).use {
        var i = it.read(buffer)
        while (i != -1) {
            md.update(buffer, 0, i)
            i = it.read(buffer)
        }
    }
    return array2str(md.digest())
}

fun sha256(filePath:String):String{
    val path = Paths.get(filePath)
    println(path)
    return sha256(path)
}

/**
Byte Array to Hex String converter
 */
fun array2str(data: ByteArray): String {
    val r = StringBuffer()
    for (i in data) {
        r.append(String.format("%02x", i))
    }
    return r.toString()
}


