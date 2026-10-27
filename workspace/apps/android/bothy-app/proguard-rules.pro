# Sample host keeps this file intentionally small.
# SDK keep rules must come from :agent-runtime consumer-proguard-rules.pro.
# The SDK keeps Companion fields only for its own packages; keep the sample's as before the package rename.
-keepclassmembers class cc.noharry.bothy.** {
    public static ** Companion;
}
