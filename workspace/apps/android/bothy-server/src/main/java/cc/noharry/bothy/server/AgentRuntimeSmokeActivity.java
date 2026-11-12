package cc.noharry.bothy.server;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import cc.noharry.agent.ThreadOptions;
import cc.noharry.agent.ApprovalPolicy;
import cc.noharry.agent.appserver.model.FixtureModelClient;
import cc.noharry.agent.AgentRuntime;
import cc.noharry.agent.runtime.AgentRuntimeBuilder;

public final class AgentRuntimeSmokeActivity extends Activity {
    private static final String SMOKE_PREFIX = "agent-runtime-smoke:";
    private static volatile String lastRuntimeSmoke;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AgentRuntime runtime = AgentRuntimeBuilder.create().model(new FixtureModelClient()).build();
        String threadId = runtime.resumeThread("sample-session", new ThreadOptions(null, null, null, null, null, ApprovalPolicy.ASK, java.util.Collections.emptySet())).getId();
        lastRuntimeSmoke = SMOKE_PREFIX + threadId + ":" + runtime.getClass().getName().length();
        runtime.close();
        View marker = new View(this);
        marker.setContentDescription(lastRuntimeSmoke);
        setContentView(marker);
    }
}
