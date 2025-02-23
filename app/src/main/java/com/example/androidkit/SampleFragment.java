package com.example.androidkit;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

public class SampleFragment extends Fragment {

    private TextView textView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_sample, container, false);
        textView = view.findViewById(R.id.textView);
        return view;
    }

    public void updateText(String text) {
        if (textView != null) {
            textView.setText(text);
        }
    }
}