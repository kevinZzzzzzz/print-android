package com.print_android.app;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Created by wyl on 2018/12/25.
 */

/**
 * 执行命令的类
 * Created by Kappa
 */
public class ExeCommand {
    private static final String TAG = "ExeCommand";
    private Context context;
    private boolean isRoot;

    public ExeCommand(Context context, boolean isRoot) {
        this.context = context;
        this.isRoot = isRoot;
    }

    public String run(String command, long timeout) {
        Process process = null;
        DataOutputStream os = null;
        StringBuilder result = new StringBuilder();

        try {
            process = Runtime.getRuntime().exec(isRoot ? "su" : "sh");
            os = new DataOutputStream(process.getOutputStream());

            // 执行命令
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            // 等待命令执行完成
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                return "Command timed out";
            }

            // 读取命令输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }

            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error executing command: " + e.getMessage());
            return "Error: " + e.getMessage();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing stream: " + e.getMessage());
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
    }
}
