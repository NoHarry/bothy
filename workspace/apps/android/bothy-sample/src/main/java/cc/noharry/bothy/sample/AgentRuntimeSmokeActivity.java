package cc.noharry.bothy.sample;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import cc.noharry.bothy.kernel.api.AgentRequest;
import cc.noharry.bothy.kernel.api.AgentRuntime;
import cc.noharry.bothy.runtime.AgentRuntimeBuilder;

public final class AgentRuntimeSmokeActivity extends Activity {
    private static final String SMOKE_PREFIX = "bothy-runtime-smoke:";
    private static volatile String lastRuntimeSmoke;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AgentRuntime runtime = AgentRuntimeBuilder.create().build();
        AgentRequest request = new AgentRequest(
                "sample-release-smoke",
                "sample-session",
                "hello",
                "release-smoke"
        );
        lastRuntimeSmoke = SMOKE_PREFIX + request.getSessionId() + ":" + runtime.getClass().getName().length();
        View marker = new View(this);
        marker.setContentDescription(lastRuntimeSmoke);
        setContentView(marker);
    }
}
