package com.example.androidkit;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.tabs.TabLayout;
import android.widget.Button;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private ViewPager viewPager;
    private EditText inputBox;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);
        inputBox = findViewById(R.id.inputBox);
        sendButton = findViewById(R.id.sendButton);

        setupViewPager(viewPager);
        tabLayout.setupWithViewPager(viewPager);

        // 处理发送按钮的点击事件
        sendButton.setOnClickListener(v -> {
            String inputText = inputBox.getText().toString();
            // 获取当前显示的Fragment
            SampleFragment currentFragment = (SampleFragment) ((ViewPagerAdapter) viewPager.getAdapter()).getItem(viewPager.getCurrentItem());
            // 更新Fragment中的文字
            currentFragment.updateText(inputText);
        });
    }

    private void setupViewPager(ViewPager viewPager) {
        ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
        adapter.addFragment(new SampleFragment(), "首页");
        adapter.addFragment(new SampleFragment(), "对话");
        adapter.addFragment(new SampleFragment(), "服务");
        viewPager.setAdapter(adapter);
    }

    class ViewPagerAdapter extends FragmentPagerAdapter {
        private final List<Fragment> fragmentList = new ArrayList<>();
        private final List<String> fragmentTitleList = new ArrayList<>();

        public ViewPagerAdapter(FragmentManager fragmentManager) {
            super(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @Override
        public Fragment getItem(int position) {
            return fragmentList.get(position);
        }

        @Override
        public int getCount() {
            return fragmentList.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return fragmentTitleList.get(position);
        }

        public void addFragment(Fragment fragment, String title) {
            fragmentList.add(fragment);
            fragmentTitleList.add(title);
        }
    }
}