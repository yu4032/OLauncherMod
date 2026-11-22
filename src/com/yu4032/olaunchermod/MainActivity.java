package com.yu4032.olaunchermod;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class MainActivity extends Activity {

    private static final String DIR =
            "/storage/emulated/0/Android/data/com.android.launcher/files";
    private static final String GRID = DIR + "/launcher_grid";

    private EditText columnsInput;
    private EditText rowsInput;
    private CheckBox keepEmpty;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("桌面网格");
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("按横屏布局设置列数 4–8、行数 3–8。"
                + "扩大网格时尽量保留现有图标位置；竖屏布局由桌面处理。"
                + "保存后重启桌面生效。");
        hint.setTextSize(12);
        hint.setPadding(0, pad / 2, 0, pad);
        root.addView(hint);

        columnsInput = numberInput();
        rowsInput = numberInput();
        root.addView(inputRow("列", columnsInput));
        root.addView(inputRow("行", rowsInput));

        keepEmpty = new CheckBox(this);
        keepEmpty.setText("保留空位（禁止自动紧凑排列图标）");
        keepEmpty.setChecked(true);
        root.addView(keepEmpty);

        Button apply = new Button(this);
        apply.setText("应用并重启桌面");
        apply.setOnClickListener(view -> applyGrid());
        root.addView(apply);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, 0);
        root.addView(status);

        setContentView(scroll);
        loadCurrent();
    }

    private EditText numberInput() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        return input;
    }

    private LinearLayout inputRow(String label, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(14);
        text.setWidth((int) (48 * getResources().getDisplayMetrics().density));
        row.addView(text);
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private String runRootRead(String command) {
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", command});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                }
                process.waitFor();
                return result.toString().trim();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean runRootCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            try (java.io.InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[512];
                while (stream.read(buffer) != -1) { /* drain output */ }
            }
            return process.waitFor() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void loadCurrent() {
        String[] values = runRootRead("cat " + GRID + " 2>/dev/null")
                .trim().split("\\s+");
        if (values.length >= 2) {
            columnsInput.setText(values[0]);
            rowsInput.setText(values[1]);
            keepEmpty.setChecked(values.length < 3 || !"0".equals(values[2]));
            status.setText("当前：" + values[0] + " 列 × " + values[1] + " 行（横屏）");
        } else {
            columnsInput.setText("8");
            rowsInput.setText("4");
            keepEmpty.setChecked(true);
            status.setText("尚未设置，推荐 8 × 4");
        }
    }

    private void applyGrid() {
        int columns;
        int rows;
        try {
            columns = Integer.parseInt(columnsInput.getText().toString().trim());
            rows = Integer.parseInt(rowsInput.getText().toString().trim());
        } catch (NumberFormatException error) {
            Toast.makeText(this, "请输入有效的列数与行数", Toast.LENGTH_SHORT).show();
            return;
        }

        if (columns < 4 || columns > 8 || rows < 3 || rows > 8) {
            Toast.makeText(this, "列数 4–8，行数 3–8", Toast.LENGTH_SHORT).show();
            return;
        }

        String config = columns + " " + rows + " " + (keepEmpty.isChecked() ? "1" : "0");
        boolean saved = runRootCommand("mkdir -p " + DIR
                + " && printf '%s\\n' '" + config + "' > " + GRID
                + " && chmod 644 " + GRID
                + " && am force-stop com.android.launcher");
        if (saved) {
            status.setText("已保存 " + columns + " 列 × " + rows + " 行，已请求桌面重启");
            Toast.makeText(this, "已保存布局", Toast.LENGTH_SHORT).show();
        } else {
            status.setText("保存失败，请确认 root 授权");
            Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show();
        }
    }
}
