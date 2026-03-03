package com.jhosue.utorrent

import org.libtorrent4j.Priority
import org.libtorrent4j.swig.byte_vector

fun test() {
    val arr = org.libtorrent4j.Priority.values()
    val ig = org.libtorrent4j.Priority.IGNORE
    val norm = org.libtorrent4j.Priority.DEFAULT
    
    val p = org.libtorrent4j.swig.add_torrent_params()
    val v = byte_vector()
    v.add(0.toByte())
}
