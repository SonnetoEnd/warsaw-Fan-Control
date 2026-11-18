package dev.k90fan.control;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

// Only the module APK's process calls su. Settings and PowerKeeper never do.
final class RootFanDriver {
    static String apply(Context context, int rpm) {
        if (rpm != FanRules.SYSTEM && !FanRules.custom(rpm)) return "invalid_limit";
        String apk = context.getApplicationInfo().sourceDir;
        String quotedApk = "'" + apk.replace("'", "'\\''") + "'";
        Process child = null;
        try (InputStream script = context.getAssets().open("apply-limit.sh")) {
            String command = "bb=/data/adb/ksu/bin/busybox; "
                    + "if [ ! -x \"$bb\" ]; then echo K90_FAN_RESULT=kernel_su_required; exit 1; fi; "
                    + "exec \"$bb\" sh -s -- " + quotedApk + " " + rpm;
            child = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            String response = "root_required";
            // Permission may be denied before the shell finishes accepting stdin.
            try (OutputStream input = child.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = script.read(buffer)) != -1) input.write(buffer, 0, n);
            } catch (IOException ignored) {}
            if (!child.waitFor(45, TimeUnit.SECONDS)) return "timeout";
            try (BufferedReader output = new BufferedReader(new InputStreamReader(
                    child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.startsWith("K90_FAN_RESULT=")) response = line.substring("K90_FAN_RESULT=".length());
                }
            }
            return "ok".equals(response) ? (child.exitValue() == 0 ? null : "driver_state") : response;
        } catch (IOException | SecurityException e) {
            return "root_required";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "timeout";
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    private RootFanDriver() {}
}
