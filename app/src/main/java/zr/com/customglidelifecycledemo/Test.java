package zr.com.customglidelifecycledemo;


import zr.com.customglidelifecycledemo.lifecycle.LifecycleListener;

/**
 * Created by zr on 2018/7/23.
 */

public class Test implements LifecycleListener {



    @Override
    public void onStart() {
        System.out.println("onStart");
    }

    @Override
    public void onStop() {
        System.out.println("onStop");
    }

    @Override
    public void onDestroy() {
        System.out.println("onDestroy");
    }
}
