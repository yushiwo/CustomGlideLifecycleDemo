package zr.com.customglidelifecycledemo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import zr.com.customglidelifecycledemo.lifecycle.LifecycleDetector;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Test test = new Test();

        LifecycleDetector.getInstance().observer(this, test);
    }
}
