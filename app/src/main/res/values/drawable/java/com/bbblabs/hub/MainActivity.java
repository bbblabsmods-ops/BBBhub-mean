package com.bbblabs.hub;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int TAB_APPS = 0;
    private static final int TAB_GAMES = 1;
    private static final int TAB_SOURCES = 2;

    private static final int CRIMSON = Color.parseColor("#DC143C");
    private static final int DARK = Color.parseColor("#1A1A1A");

    // Stores that count as "known". Anything else (adb, file manager, browser) = unknown source.
    private static final Set<String> KNOWN_STORES = new HashSet<>(Arrays.asList(
            "com.android.vending",
            "com.oculus.horizon",
            "com.oculus.store",
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
            "com.xiaomi.mipicks"
    ));

    private static class Item {
        String label;
        String pkg;
        Drawable icon;
        Intent launch;
        boolean system;
        boolean game;
        boolean unknownSource;
    }

    private final List<Item> allItems = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final Button[] tabs = new Button[3];
    private ItemAdapter adapter;
    private TextView status;
    private int currentTab = TAB_APPS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh in case something was installed or removed
        if (adapter != null) {
            loadApps();
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(24), dp(24), dp(16));

        TextView title = new TextView(this);
        title.setText("BBB Hub");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(16);
        root.addView(row, rowLp);

        String[] names = {"Apps", "Games", "Sources"};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(18);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(tab);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(b, lp);
            tabs[i] = b;
        }

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(14);
        status.setPadding(dp(4), dp(12), dp(4), dp(8));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        adapter = new ItemAdapter(shown);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                openItem(shown.get(position));
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                openAppInfo(shown.get(position));
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        styleTabs();
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            boolean on = (i == currentTab);
            tabs[i].setBackgroundColor(on ? CRIMSON : DARK);
            tabs[i].setTextColor(Color.WHITE);
        }
    }

    private void selectTab(int tab) {
        currentTab = tab;
        styleTabs();
        refreshList();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- Data

    private void loadApps() {
        status.setText("Loading...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> result = new ArrayList<>();
                try {
                    PackageManager pm = getPackageManager();
                    List<ApplicationInfo> installed = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : installed) {
                        try {
                            Intent launch = pm.getLaunchIntentForPackage(ai.packageName);
                            if (launch == null && Build.VERSION.SDK_INT >= 21) {
                                launch = pm.getLeanbackLaunchIntentForPackage(ai.packageName);
                            }
                            if (launch == null) continue; // not something you can open

                            Item it = new Item();
                            it.pkg = ai.packageName;
                            it.launch = launch;
                            CharSequence label = pm.getApplicationLabel(ai);
                            it.label = label == null ? ai.packageName : label.toString();
                            it.icon = pm.getApplicationIcon(ai);
                            it.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                            it.game = isGame(ai);
                            it.unknownSource = !it.system && isUnknownSource(pm, ai.packageName);
                            result.add(it);
                        } catch (Throwable ignored) {
                            // skip any app that misbehaves
                        }
                    }
                } catch (Throwable t) {
                    // fall through with whatever we have
                }

                Collections.sort(result, new Comparator<Item>() {
                    @Override
                    public int compare(Item a, Item b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        allItems.clear();
                        allItems.addAll(result);
                        refreshList();
                    }
                });
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private boolean isGame(ApplicationInfo ai) {
        if (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) {
            return true;
        }
        return (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    @SuppressWarnings("deprecation")
    private boolean isUnknownSource(PackageManager pm, String pkg) {
        String installer = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                installer = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            } else {
                installer = pm.getInstallerPackageName(pkg);
            }
        } catch (Throwable ignored) {
        }
        return installer == null || !KNOWN_STORES.contains(installer);
    }

    private void refreshList() {
        shown.clear();
        for (Item it : allItems) {
            boolean match;
            switch (currentTab) {
                case TAB_GAMES:
                    match = it.game;
                    break;
                case TAB_SOURCES:
                    match = it.unknownSource;
                    break;
                default:
                    match = true;
                    break;
            }
            if (match) shown.add(it);
        }
        adapter.notifyDataSetChanged();

        String[] names = {"apps", "games", "unknown source apps"};
        status.setText(shown.size() + " " + names[currentTab]
                + "  •  tap to open, hold for app info");
    }

    // ---------------------------------------------------------------- Actions

    private void openItem(Item it) {
        try {
            startActivity(it.launch);
        } catch (Throwable t) {
            Toast.makeText(this, "Can't open " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo(Item it) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + it.pkg));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Can't open app info", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- Adapter

    private class ItemAdapter extends ArrayAdapter<Item> {
        ItemAdapter(List<Item> data) {
            super(MainActivity.this, 0, data);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView name;
            TextView pkg;

            if (convertView == null) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundColor(DARK);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));

                icon = new ImageView(getContext());
                icon.setId(1);
                row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

                LinearLayout col = new LinearLayout(getContext());
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                colLp.leftMargin = dp(14);
                row.addView(col, colLp);

                name = new TextView(getContext());
                name.setId(2);
                name.setTextColor(Color.WHITE);
                name.setTextSize(18);
                name.setSingleLine(true);
                col.addView(name);

                pkg = new TextView(getContext());
                pkg.setId(3);
                pkg.setTextColor(Color.GRAY);
                pkg.setTextSize(12);
                pkg.setSingleLine(true);
                col.addView(pkg);
            } else {
                row = (LinearLayout) convertView;
                icon = (ImageView) row.findViewById(1);
                name = (TextView) row.findViewById(2);
                pkg = (TextView) row.findViewById(3);
            }

            Item it = getItem(position);
            icon.setImageDrawable(it.icon);
            name.setText(it.label);
            pkg.setText(it.pkg);
            return row;
        }
    }
}
