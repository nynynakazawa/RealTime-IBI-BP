package com.example.realtimehribicontrol;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ModeSelectionFragment extends Fragment {

    public interface OnModeSelectedListener {
        void onModeSelected(int mode);
    }

    private OnModeSelectedListener listener;

    private static final int MODE_1 = 1;
    private static final int MODE_2 = 2;
    private static final int MODE_3 = 3;
    private static final int MODE_4 = 4;
    private static final int MODE_5 = 5;
    private static final int MODE_6 = 6;
    private static final int MODE_7 = 7;
    private static final int MODE_8 = 8;
    private static final int MODE_9 = 9;
    private static final int MODE_10 = 10;

    private RadioGroup modeRadioGroup;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mode_selection, container, false);

        modeRadioGroup = view.findViewById(R.id.modeRadioGroup);

        modeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int selectedMode = getSelectedMode();
            if (listener != null) {
                listener.onModeSelected(selectedMode);
            }
            closeFragment();
        });

        return view;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnModeSelectedListener) {
            listener = (OnModeSelectedListener) context;
        } else {
            throw new RuntimeException(context
                    + " must implement OnModeSelectedListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    private int getSelectedMode() {
        int selectedId = modeRadioGroup.getCheckedRadioButtonId();
        if (selectedId == R.id.mode_1) {
            return MODE_1;
        } else if (selectedId == R.id.mode_2) {
            return MODE_2;
        } else if (selectedId == R.id.mode_3) {
            return MODE_3;
        } else if (selectedId == R.id.mode_4) {
            return MODE_4;
        } else if (selectedId == R.id.mode_5) {
            return MODE_5;
        } else if (selectedId == R.id.mode_6) {
            return MODE_6;
        } else if (selectedId == R.id.mode_7) {
            return MODE_7;
        } else if (selectedId == R.id.mode_8) {
            return MODE_8;
        } else if (selectedId == R.id.mode_9) {
            return MODE_9;
        } else if (selectedId == R.id.mode_10) {
            return MODE_10;
        } else {
            return -1;
        }
    }

    private void closeFragment() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().beginTransaction().remove(this).commit();
            getActivity().findViewById(R.id.mode_select_fragment_container).setVisibility(View.GONE);
            getActivity().findViewById(R.id.show_mode_select_fragment_button).setVisibility(View.VISIBLE);
        }
    }
}
