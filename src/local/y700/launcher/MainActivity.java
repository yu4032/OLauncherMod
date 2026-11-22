package local.y700.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {

    private static final String DIR = "/storage/emulated/0/Android/data/com.android.launcher/files";
    private static final String GRID = DIR + "/y700_grid";

    private EditText cols;
    private EditText rows;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

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
        hint.setText("按横屏语义填写：列 4–8，行 3–8。保存后自动重启桌面；竖屏由 Launcher 自动转置为 行×列。");
        hint.setTextSize(12);
        hint.setPadding(0, pad / 2, 0, pad);
        root.addView(hint);

        cols = input();
        rows = input();
        root.addView(row("列", cols));
        root.addView(row("行", rows));

        Button apply = new Button(this);
        apply.setText("应用并重启桌面");
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyGrid();
            }
        });
        root.addView(apply);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, 0);
        root.addView(status);

        setContentView(scroll);
        loadCurrent();
    }

    private EditText input() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout row(String label, EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setWidth((int) (48 * getResources().getDisplayMetrics().density));
        row.addView(t);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void loadCurrent() {
        String g = su("cat " + GRID + " 2>/dev/null");
        String[] gp = g.trim().split("\\s+");
        if (gp.length >= 2) {
            cols.setText(gp[0]);
            rows.setText(gp[1]);
            status.setText("当前：" + gp[0] + " 列 × " + gp[1] + " 行（横屏）");
        } else {
            cols.setText("8");
            rows.setText("4");
            status.setText("当前未设置，默认 8 × 4");
        }
    }

    private void applyGrid() {
        int c;
        int r;
        try {
            c = Integer.parseInt(cols.getText().toString().trim());
            r = Integer.parseInt(rows.getText().toString().trim());
        } catch (Throwable t) {
            Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (c < 4 || c > 8 || r < 3 || r > 8) {
            Toast.makeText(this, "列 4–8，行 3–8", Toast.LENGTH_SHORT).show();
            return;
        }
        su("mkdir -p " + DIR
                + "; echo '" + c + " " + r + "' > " + GRID
                + "; chmod 644 " + GRID
                + "; am force-stop com.android.launcher");
        status.setText("已保存 " + c + " 列 × " + r + " 行，桌面重启中");
        Toast.makeText(this, "已保存，桌面重启后生效", Toast.LENGTH_SHORT).show();
    }
}
