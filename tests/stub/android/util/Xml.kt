package android.util

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * Desktop stand-in for Android's Xml.newPullParser(), which is Expat-backed on device.
 * Only used by the JVM test build (tests/), never shipped in the APK.
 */
object Xml {
    fun newPullParser(): XmlPullParser = KXmlParser()
}
