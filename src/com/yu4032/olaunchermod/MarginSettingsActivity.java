package com.yu4032.olaunchermod;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * LiquidDock-style workspace position controls implemented with native Android
 * widgets to keep OLauncherMod's small Java/LSPosed build dependency-free.
 *
 * Landscape and portrait have independent horizontal/top/bottom dp values.
 * Sliders preview the pending value, value chips open precise numeric edit,
 * steppers change by 1 dp, and each row has a reset action. Nothing writes
 * Launcher state until explicit Apply: a controlled process restart is needed.
 */
public final class MarginSettingsActivity extends Activity {
    private static final String DIRECTORY =
            "/storage/emulated/0/Android/data/com.android.launcher/files";
    private static final String CONFIG = DIRECTORY + "/launcher_workspace_margins";
    private static final int LIMIT = WorkspaceMarginConfig.LIMIT_DP;
    private static final String[] TITLES = {
            "水平距离偏移", "顶部距离偏移", "底部距离偏移"
    };
    private static final String[] SUMMARIES = {
            "同时调整工作区左右两侧的留白距离",
            "基于原生工作区顶部边距调整；不移动 Dock",
            "基于原生工作区底部边距调整；不移动 Dock"
    };

    private WorkspaceMarginConfig draft = WorkspaceMarginConfig.DEFAULT;
    private boolean portrait;
    private boolean dirty;
    private boolean loaded;
    private boolean legacyAsymmetricOnLoad;
    private Button landscapeTab;
    private Button portraitTab;
    private Button apply;
    private LinearLayout controls;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        final int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("工作区位置");
        title.setTextSize(22);
        root.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("参考 LiquidDock：横竖屏独立调整水平、顶部和底部距离。"
                + "滑动调节、点击数值精确输入，± 键每次微调 1dp。"
                + "0dp 使用系统原生边距，修改后应用并重启桌面。");
        explanation.setTextSize(13);
        explanation.setPadding(0, dp(8), 0, dp(16));
        root.addView(explanation);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        landscapeTab = new Button(this);
        landscapeTab.setText("横屏布局");
        portraitTab = new Button(this);
        portraitTab.setText("竖屏布局");
        tabs.addView(landscapeTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(portraitTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        landscapeTab.setOnClickListener(v -> selectOrientation(false));
        portraitTab.setOnClickListener(v -> selectOrientation(true));
        root.addView(tabs);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);

        LinearLayout resetActions = new LinearLayout(this);
        resetActions.setOrientation(LinearLayout.HORIZONTAL);
        Button resetPage = new Button(this);
        resetPage.setText("重置当前方向");
        resetPage.setOnClickListener(v -> {
            draft = draft.resetOrientation(portrait);
            markDirty();
            rebuildControls();
        });
        Button resetAll = new Button(this);
        resetAll.setText("全部恢复默认");
        resetAll.setOnClickListener(v -> {
            draft = WorkspaceMarginConfig.DEFAULT;
            markDirty();
            rebuildControls();
        });
        resetActions.addView(resetPage, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resetActions.addView(resetAll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(resetActions);

        apply = new Button(this);
        apply.setText("应用边距并重启桌面");
        apply.setOnClickListener(v -> confirmSave());
        root.addView(apply);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(10));
        root.addView(status);
        setContentView(scroll);
        rebuildControls();

        status.setText("读取当前配置…");
        new Thread(() -> {
            String text = rootRead();
            WorkspaceMarginConfig read = WorkspaceMarginConfig.DEFAULT;
            String warning = null;
            try {
                if (!text.isEmpty()) read = WorkspaceMarginConfig.parse(text);
            } catch (IllegalArgumentException e) {
                warning = "现有边距配置无效，显示默认值；原文件尚未修改。";
            }
            final WorkspaceMarginConfig next = read;
            final String message = warning;
            runOnUiThread(() -> {
                loaded = true;
                if (!dirty) {
                    draft = next;
                    legacyAsymmetricOnLoad = next.isLegacyAsymmetric();
                    rebuildControls();
                }
                status.setText(message != null ? message : next.isLegacy()
                        ? "已读取旧版四边距配置；首次修改保存时转换为横竖屏独立配置。"
                        : "配置已读取。改动仅在点击应用后生效。");
            });
        }, "OLauncherMod-margin-read").start();
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    private void selectOrientation(boolean next) {
        if (portrait == next) return;
        portrait = next;
        rebuildControls();
    }

    private void rebuildControls() {
        landscapeTab.setAlpha(portrait ? 0.55f : 1f);
        portraitTab.setAlpha(portrait ? 1f : 0.55f);
        controls.removeAllViews();
        for (int axis = 0; axis < 3; axis++) {
            addNumericControl(axis);
        }
    }

    private void addNumericControl(int axis) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(10), 0, dp(4));
        TextView heading = new TextView(this);
        heading.setText(TITLES[axis]);
        heading.setTextSize(16);
        TextView summary = new TextView(this);
        summary.setText(SUMMARIES[axis]);
        summary.setTextSize(12);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(heading);
        labels.addView(summary);
        header.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button valueChip = new Button(this);
        valueChip.setAllCaps(false);
        valueChip.setText(draft.get(portrait, axis) + " dp");
        valueChip.setOnClickListener(v -> editValue(axis));
        header.addView(valueChip);
        group.addView(header);

        SeekBar slider = new SeekBar(this);
        slider.setMax(LIMIT * 2);
        slider.setProgress(draft.get(portrait, axis) + LIMIT);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                    boolean fromUser) {
                if (fromUser) {
                    setValue(axis, progress - LIMIT);
                    valueChip.setText((progress - LIMIT) + " dp");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        group.addView(slider);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("重置");
        Button minus = new Button(this);
        minus.setText("−");
        Button plus = new Button(this);
        plus.setText("+");
        reset.setOnClickListener(v -> updateControl(axis, 0, slider, valueChip));
        minus.setOnClickListener(v -> updateControl(axis,
                draft.get(portrait, axis) - 1, slider, valueChip));
        plus.setOnClickListener(v -> updateControl(axis,
                draft.get(portrait, axis) + 1, slider, valueChip));
        buttons.addView(reset);
        buttons.addView(minus);
        buttons.addView(plus);
        group.addView(buttons);
        controls.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View separator = new View(this);
        separator.setBackgroundColor(0x22888888);
        controls.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void updateControl(int axis, int newValue, SeekBar slider, Button chip) {
        int value = Math.max(-LIMIT, Math.min(LIMIT, newValue));
        setValue(axis, value);
        slider.setProgress(value + LIMIT);
        chip.setText(value + " dp");
    }

    private void setValue(int axis, int value) {
        if (draft.get(portrait, axis) == value && !draft.isLegacy()) return;
        draft = draft.with(portrait, axis, value);
        markDirty();
    }

    private void markDirty() {
        dirty = true;
        status.setText("有未应用的调整。保存后重启桌面生效。");
    }

    private void editValue(int axis) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(Integer.toString(draft.get(portrait, axis)));
        input.selectAll();
        final boolean editedPortrait = portrait;
        new AlertDialog.Builder(this)
                .setTitle(TITLES[axis] + "（-600～600 dp）")
                .setView(input)
                .setNegativeButton("取消", (dialog, which) -> { })
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < -LIMIT || value > LIMIT) {
                            throw new NumberFormatException("out of range");
                        }
                        draft = draft.with(editedPortrait, axis, value);
                        markDirty();
                        rebuildControls();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入 -600 至 600 的整数",
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void confirmSave() {
        if (!loaded) {
            Toast.makeText(this, "当前配置尚未读取完毕", Toast.LENGTH_SHORT).show();
            return;
        }
        if (legacyAsymmetricOnLoad && !draft.isLegacy()) {
            new AlertDialog.Builder(this)
                    .setTitle("旧版左右边距不对称")
                    .setMessage("旧设置的左右边距不同。LiquidDock 式“水平距离”"
                            + "会对左右两侧使用相同增量；新版将使用旧值的平均值"
                            + "作为首次默认水平距离。是否继续保存？")
                    .setNegativeButton("取消", (dialog, which) -> { })
                    .setPositiveButton("继续", (dialog, which) -> save())
                    .show();
        } else {
            save();
        }
    }

    private void save() {
        apply.setEnabled(false);
        final String text = draft.serialize();
        new Thread(() -> {
            boolean success = rootWrite(text);
            runOnUiThread(() -> {
                apply.setEnabled(true);
                if (success) {
                    dirty = false;
                    legacyAsymmetricOnLoad = false;
                    status.setText("已应用。等待系统桌面重新启动后，检查实际位置。");
                    Toast.makeText(this, "边距配置已保存", Toast.LENGTH_SHORT).show();
                } else {
                    status.setText("保存失败；配置保留在当前页面，请检查 Root 授权。");
                }
            });
        }, "OLauncherMod-margin-write").start();
    }

    private static String rootRead() {
        try {
            Process p = new ProcessBuilder("su", "-c", "cat " + CONFIG + " 2>/dev/null")
                    .redirectErrorStream(true).start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line).append('\n');
            }
            return p.waitFor() == 0 ? text.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean rootWrite(String content) {
        // Content is a parser-validated space-separated sequence of integers.
        String command = "mkdir -p " + DIRECTORY
                + " && printf '%s\\n' '" + content + "' > " + CONFIG
                + " && chmod 644 " + CONFIG
                + " && rm -f " + DIRECTORY + "/launcher_margin_status"
                + " && am force-stop com.android.launcher";
        try {
            Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            try (java.io.InputStream stream = p.getInputStream()) {
                byte[] bytes = new byte[256];
                while (stream.read(bytes) != -1) { }
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
