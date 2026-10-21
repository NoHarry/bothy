-keep class cc.noharry.bothy.kernel.api.** { *; }
-keep class cc.noharry.bothy.kernel.core.KernelRuntimeBuilder { *; }
-keep class cc.noharry.bothy.kernel.core.CoreAgentRuntime { *; }
-keep class cc.noharry.bothy.minimal.MinimalAgent { *; }
-keep class cc.noharry.bothy.runtime.AgentRuntimeBuilder { *; }
-keep class cc.noharry.bothy.runtime.RuntimeWorkspaceRef { *; }
-keep class cc.noharry.bothy.runtime.HostSkill { *; }
-keep class cc.noharry.bothy.runtime.NetworkToolConfirmationPolicy { *; }
-keep class cc.noharry.bothy.runtime.eventlog.DurableSessionEventLogPlugin { *; }
-keep class cc.noharry.bothy.dream.** { *; }
-keep class cc.noharry.bothy.model.** { *; }
-keep class cc.noharry.bothy.policy.** { *; }
-keep class cc.noharry.bothy.transport.** { *; }
-keep class cc.noharry.bothy.vfs.** { *; }
-keepclassmembers class cc.noharry.bothy.** {
    public static ** Companion;
}
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
