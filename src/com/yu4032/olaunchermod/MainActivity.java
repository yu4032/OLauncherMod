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
    private static final String MARGINS = DIR + "/launcher_workspace_margins";

    private EditText columnsInput;
    private EditText rowsInput;
    private CheckBox keepEmpty;
    private EditText leftMarginInput;
    private EditText topMarginInput;
    private EditText rightMarginInput;
    private EditText bottomMarginInput;
    private TextView marginStatus;
    private TextView status;
    private TextView runtimeStatus;

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
        title.setText("桌面工作区网格");
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("设置横屏网格：列数 4–8、行数 3–8。"
                + "采用平板旋转布局时，竖屏会交换行列。"
                + "修改后重启桌面，检查下方工作区实际网格。");
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
        status.setPadding(0, pad / 2, 0, pad);
        root.addView(status);

        TextView marginsTitle = new TextView(this);
        marginsTitle.setText("工作区位置与边距");
        marginsTitle.setTextSize(18);
        root.addView(marginsTitle);

        TextView marginsHint = new TextView(this);
        marginsHint.setText("在系统原生边距基础上增减，单位 dp（-160～160）。"
                + "正数增加留白，负数缩小留白。"
                + "左侧增加会把图标推向右侧；上侧增加会把图标推向下方，"
                + "右、下两侧同理。"
                + "同时调整相对两侧，可移动工作区而尽量保持可用面积。"
                + "横竖屏均按各自物理方向应用，不修改图标坐标。"
                + "全部为 0 时完全使用系统原始布局。");
        marginsHint.setTextSize(12);
        marginsHint.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(marginsHint);

        leftMarginInput = signedNumberInput();
        topMarginInput = signedNumberInput();
        rightMarginInput = signedNumberInput();
        bottomMarginInput = signedNumberInput();
        root.addView(inputRow("左 (dp)", leftMarginInput));
        root.addView(inputRow("上 (dp)", topMarginInput));
        root.addView(inputRow("右 (dp)", rightMarginInput));
        root.addView(inputRow("下 (dp)", bottomMarginInput));

        Button applyMargins = new Button(this);
        applyMargins.setText("应用边距并重启桌面");
        applyMargins.setOnClickListener(view -> applyMargins());
        root.addView(applyMargins);

        Button resetMargins = new Button(this);
        resetMargins.setText("恢复系统默认边距");
        resetMargins.setOnClickListener(view -> {
            leftMarginInput.setText("0");
            topMarginInput.setText("0");
            rightMarginInput.setText("0");
            bottomMarginInput.setText("0");
            applyMargins();
        });
        root.addView(resetMargins);

        marginStatus = new TextView(this);
        marginStatus.setPadding(0, pad / 2, 0, pad);
        root.addView(marginStatus);

        Button refresh = new Button(this);
        refresh.setText("刷新工作区实际网格");
        refresh.setOnClickListener(view -> loadRuntimeStatus());
        root.addView(refresh);

        runtimeStatus = new TextView(this);
        runtimeStatus.setTextIsSelectable(true);
        runtimeStatus.setTextSize(13);
        runtimeStatus.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(runtimeStatus);

        setContentView(scroll);
        loadCurrent();
        loadMarginCurrent();
        loadRuntimeStatus();
    }

    private EditText numberInput() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        return input;
    }

    private EditText signedNumberInput() {
        EditText input = numberInput();
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private LinearLayout inputRow(String label, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(14);
        text.setWidth((int) (76 * getResources().getDisplayMetrics().density));
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

    private void loadRuntimeStatus() {
        String content = runRootRead("cat " + DIR
                + "/launcher_grid_status 2>/dev/null");
        String loaderContent = runRootRead("cat " + DIR
                + "/launcher_loader_status 2>/dev/null");
        String marginContent = runRootRead("cat " + DIR
                + "/launcher_margin_status 2>/dev/null");
        String workspaceReport = content.isEmpty()
                ? "尚无工作区报告：请确认 LSPosed 作用域后重启桌面。"
                : content;
        String loaderReport = loaderContent.isEmpty()
                ? "尚无加载器边界报告：说明 yn9.v 尚未调用，"
                    + "或最终边界 Hook 未安装。检查 LSPosed 日志。"
                : loaderContent;
        runtimeStatus.setText("工作区实际网格：\n" + workspaceReport
                + "\n启动加载器边界：\n" + loaderReport
                + "\n横屏应为 8x4，竖屏应为 4x8。"
                + "\n工作区边距：\n"
                + (marginContent.isEmpty() ? "当前使用默认边距，或尚无运行状态报告。" : marginContent)
                + "\n如果出现布局偏移异常，请同时提供本报告。");
    }

    private void loadMarginCurrent() {
        String value = runRootRead("cat " + MARGINS + " 2>/dev/null");
        WorkspaceMarginConfig config;
        try {
            config = value.isEmpty() ? WorkspaceMarginConfig.DEFAULT
                    : WorkspaceMarginConfig.parse(value);
        } catch (IllegalArgumentException error) {
            config = WorkspaceMarginConfig.DEFAULT;
            marginStatus.setText("边距文件无效，显示系统默认值。请重新保存。");
        }
        leftMarginInput.setText(Integer.toString(config.leftDp));
        topMarginInput.setText(Integer.toString(config.topDp));
        rightMarginInput.setText(Integer.toString(config.rightDp));
        bottomMarginInput.setText(Integer.toString(config.bottomDp));
        if (marginStatus.getText().length() == 0) {
            marginStatus.setText(config.isDefault()
                    ? "当前：系统默认边距"
                    : "当前边距增量：" + config.serialize() + " dp（左 上 右 下）");
        }
    }

    private void applyMargins() {
        WorkspaceMarginConfig config;
        try {
            String line = leftMarginInput.getText().toString().trim() + " "
                    + topMarginInput.getText().toString().trim() + " "
                    + rightMarginInput.getText().toString().trim() + " "
                    + bottomMarginInput.getText().toString().trim();
            config = WorkspaceMarginConfig.parse(line);
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, "四个边距都必须在 -160～160 dp 之间",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean saved = runRootCommand("mkdir -p " + DIR
                + " && printf '%s\\n' '" + config.serialize() + "' > " + MARGINS
                + " && chmod 644 " + MARGINS
                + " && rm -f " + DIR + "/launcher_margin_status"
                + " && am force-stop com.android.launcher");
        if (saved) {
            marginStatus.setText(config.isDefault()
                    ? "已恢复系统默认边距，桌面正在重新启动。"
                    : "已保存边距：" + config.serialize()
                      + " dp，桌面正在重新启动。");
            Toast.makeText(this, "边距已保存", Toast.LENGTH_SHORT).show();
        } else {
            marginStatus.setText("保存失败，请检查 Root 授权。");
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
                + " && rm -f " + DIR + "/launcher_grid_status"
                + " " + DIR + "/launcher_loader_status"
                + " && am force-stop com.android.launcher");
        if (saved) {
            status.setText("已保存横屏网格 " + columns + " 列 × " + rows
                    + " 行。待桌面恢复后点击刷新实际网格。");
            runtimeStatus.setText("正在重启桌面，旧状态报告已清除。");
            Toast.makeText(this, "已保存布局", Toast.LENGTH_SHORT).show();
        } else {
            status.setText("保存失败，请确认 root 授权");
            Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show();
        }
    }
}
