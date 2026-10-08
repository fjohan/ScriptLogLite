package se.lu.scriptloglite;

import java.util.List;

final class RecordingVariables {
    final String experiment, condition, subject;
    RecordingVariables(String experiment, String condition, String subject) {
        for (String value : List.of(experiment, condition, subject)) {
            if (!value.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid recording identifier");
        }
        this.experiment = experiment; this.condition = condition; this.subject = subject;
        if (prefix().equals(".") || prefix().equals("..")) throw new IllegalArgumentException("Invalid recording directory");
    }
    String prefix() { return experiment + condition + subject; }
}
