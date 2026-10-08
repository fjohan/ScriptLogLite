package se.lu.scriptloglite;

import org.junit.jupiter.api.Test;

/** Runs the regression suite through Maven and the NetBeans Test Project action. */
class ScriptLogLiteTest {
    @Test
    void regressionSuite() throws Exception {
        ScriptLogLiteChecks.testReplay();
    }
}
