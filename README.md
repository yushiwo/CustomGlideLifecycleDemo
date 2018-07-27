## 如何绑定页面生命周期（一）－Glide实现

>Glide中一个重要的特性，就是Request可以随着Activity或Fragment的onStart而resume，onStop而pause，onDestroy而clear。从而节约流量和内存，并且防止内存泄露，这一切都由Glide在内部实现了。用户唯一要注意的是，Glide.with()方法中尽量传入Activity或Fragment，而不是Application，不然没办法进行生命周期管理。

因为对Glide绑定生命周期的原理很感兴趣，所以看了一些源码解析的文章，也读了Glide的相关源码。发现大多数对于Glide生命周期绑定原理的介绍，是直接通过源码一步步的介绍。个人感觉这样没有重点，容易迷失在代码流程细节中。

所以这篇文章通过另外一种方式介绍Glide生命周期管理的原理，即通过提问解答的方式，带着问题阅读，更加具有针对性。介绍完了原理之后，我们通过基于Glide生命周期感知的原理，实现了一个仿Glide生命周期管理框架的demo，进一步加深巩固之前所学知识点。所以，本文介绍主要分为两个部分：

+ Glide生命周期管理原理
+ 仿Glide自定义生命周期管理框架实践

### Glide生命周期管理原理
这里的话，我主要提了三个问题：

+ 总体实现原理
+ 如何绑定生命周期
+ 如何传递生命周期

下面通过解答这三个问题，让我们一起来探究下Glide绑定生命周期的实现原理。本文以Activity为例进行讲解。

#### 总体实现原理
基于当前Activity添加无UI的Fragment，通过Fragment接收Activity传递的生命周期。Fragment和RequestManager基于Lifecycle建立联系，并传递生命周期事件，实现生命周期感知。分析上述的原理，可以归纳为两个方面：

1. 如何基于当前传入Activity生成无UI的Fragment，即如何实现对页面的周期绑定。
2. 无UI的fragment如何将生命周期传递给RequestManager，即如何实现生命周期传递。

#### 如何绑定生命周期
使用Glide时，我们通过`Glide.with(Activity activity)`的方式传入页面引用，让我们看下`with(Activity activity)`方法的实现:

```
public static RequestManager with(Activity activity) {
    RequestManagerRetriever retriever = RequestManagerRetriever.get();
    return retriever.get(activity);
}
```

`with(Activity activity)`在方法体内先获取了`RequestManagerRetriever`实例`retriever`，然后通过`retriever`去调用成员函数`get(activity)`。接下来我们看下`get(activity)`的实现:

```
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public RequestManager get(Activity activity) {
    if (Util.isOnBackgroundThread() || Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
        return get(activity.getApplicationContext());
    } else {
        assertNotDestroyed(activity);
        // 获取当前Activity的FragmentManager
        android.app.FragmentManager fm = activity.getFragmentManager();
        return fragmentGet(activity, fm);
    }
}
```

我们看上面函数方法体代码，当应用在后台或系统低于HONEYCOMB版本，则直接绑定应用的生命周期，这里我们主要看else部分的代码。

首先，通过传入的activity引用，获取当前页面的FragmentManager，然后将当前页面的引用和刚生成的FragmentManager对象引用，作为参数一起传入`fragmentGet(activity, fm)`方法。下面看下`fragmentGet(activity, fm)`的具体实现：

```
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
RequestManager fragmentGet(Context context, android.app.FragmentManager fm) {
	// 基于当前activity注册无UI的RequestManagerFragment
    RequestManagerFragment current = getRequestManagerFragment(fm);
    // 生成RequestManager
    RequestManager requestManager = current.getRequestManager();
    if (requestManager == null) {
    	// 通过current.getLifecycle()获取fragment的lifecycle，传入requestManager，将fragment和requestManager建立联系
        requestManager = new RequestManager(context, current.getLifecycle(), current.getRequestManagerTreeNode());
        current.setRequestManager(requestManager);
    }
    return requestManager;
}
```

上述方法具体执行的步骤，如上注释所示：

1. 基于当前activity注册无UI的RequestManagerFragment
2. 生成RequestManager,通过current.getLifecycle()获取fragment的lifecycle，传入requestManager，将fragment和requestManager建立联系

这里有两点需要我们关注下：

1. 通过`getRequestManagerFragment(fm)`生成无UI的fragment

生成fragment时，最终会调用到`RequestManagerFragment`的构造方法，实现形式如下：

```
public RequestManagerFragment() {
    this(new ActivityFragmentLifecycle());
}

// For testing only.
@SuppressLint("ValidFragment")
RequestManagerFragment(ActivityFragmentLifecycle lifecycle) {
    this.lifecycle = lifecycle;
}
```

构造fragment时，会同时初始化成员变量`lifecycle`。

2. 生成RequestManager对象时，通过`current.getLifecycle()`获取fragment的成员lifecycle，作为参数传入RequestManager构造函数。

```
public RequestManager(Context context, Lifecycle lifecycle, RequestManagerTreeNode treeNode) {
    this(context, lifecycle, treeNode, new RequestTracker(), new ConnectivityMonitorFactory());
}

RequestManager(Context context, final Lifecycle lifecycle, RequestManagerTreeNode treeNode,
        RequestTracker requestTracker, ConnectivityMonitorFactory factory) {
    this.context = context.getApplicationContext();
    this.lifecycle = lifecycle;
    this.treeNode = treeNode;
    this.requestTracker = requestTracker;
    this.glide = Glide.get(context);
    this.optionsApplier = new OptionsApplier();

    ConnectivityMonitor connectivityMonitor = factory.build(context,
            new RequestManagerConnectivityListener(requestTracker));

    // If we're the application level request manager, we may be created on a background thread. In that case we
    // cannot risk synchronously pausing or resuming requests, so we hack around the issue by delaying adding
    // ourselves as a lifecycle listener by posting to the main thread. This should be entirely safe.
    if (Util.isOnBackgroundThread()) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                lifecycle.addListener(RequestManager.this);
            }
        });
    } else {
        lifecycle.addListener(this);
    }
    lifecycle.addListener(connectivityMonitor);
}
```

可见在RequestManager初始化时，调用了`lifecycle.addListener(this)`，将自己的引用存入lifecycle，从而实现与fragment关联。

建立了联系，下面我们看下生命周期是如何传递的。

#### 如何传递生命周期

通过上面生命周期绑定的流程，我们已经知道通过ActivityFragmentLifecycle，将空白Fragment和RequestManager建立了联系。因为空白fragment注册在页面上，其可以感知页面的生命周期。下面我们来看下如何从空白fragment，将生命周期传递给RequestManager，从而对Request进行管理。

首先，我们来看空白RequestManagerFragment生命周期回调方法：

```
...
@Override
public void onStart() {
    super.onStart();
    lifecycle.onStart();
}

@Override
public void onStop() {
    super.onStop();
    lifecycle.onStop();
}

@Override
public void onDestroy() {
    super.onDestroy();
    lifecycle.onDestroy();
}
...

```

我们看到会调用其成员对象lifecycle相关对应生命周期的回调方法，这里我们以onStart()为例，看一下ActivityFragmentLifecycle中的方法实现：

```
void onStart() {
    isStarted = true;
    for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
        lifecycleListener.onStart();
    }
}
```

可见回调lifeCycleListener中的相关方法，因为RequestManager实现了lifeCycleListener接口。且在绑定阶段，在RequestManager的构造方法中，将RequestManager加入到了lifeCycle中。故回调lifeCycleListener中的相关方法，可以调用到它里面的对request生命周期进行管理的方法。由此，实现了Request对生命周期的感知。

```
 /**
 * Lifecycle callback that registers for connectivity events (if the android.permission.ACCESS_NETWORK_STATE
 * permission is present) and restarts failed or paused requests.
 */
@Override
public void onStart() {
    // onStart might not be called because this object may be created after the fragment/activity's onStart method.
    resumeRequests();
}

/**
 * Lifecycle callback that unregisters for connectivity events (if the android.permission.ACCESS_NETWORK_STATE
 * permission is present) and pauses in progress loads.
 */
@Override
public void onStop() {
    pauseRequests();
}

/**
 * Lifecycle callback that cancels all in progress requests and clears and recycles resources for all completed
 * requests.
 */
@Override
public void onDestroy() {
    requestTracker.clearRequests();
}
```

基于生命周期传递的过程，画了下生命周期传递的示意图，如下所示：

![Glide中生命周期传递](http://on-img.com/chart_image/5b55438ce4b053a09c10a29d.png)


### 几个核心类介绍

通过对Glide生命周期绑定和传递整个流程过了一遍之后，大家应该对整体实现的框架有一定的了解。现在再来看下面一些核心类的介绍，应该更加有感触。

+ Glide：库提供对外调用方法的类，传入页面引用。
+ RequestManagerRetriever：一个处理中间类，获取RequestManager和RequestManagerFragment，并将两者绑定
+ RequestManagerFragment：无UI的fragment，与RequestManager绑定，感知并传递页面的生命周期
+ RequestManager：实现了LifeCycleListener，主要作用为结合Activity或Fragment生命周期，对Request进行管理，如pauseRequests(), resumeRequests(), clearRequests()。
+ LifecycleListener：接口，定义生命周期管理方法，onStart(), onStop(), onDestroy(). RequestManager实现了它。
+ ActivityFragmentLifecycle：保存fragment和Requestmanager映射关系的类，管理LifecycleListener， 空白Fragment会回调它的onStart(), onStop(), onDestroy()。


### 生命周期管理框架实践
理解了Glide的生命周期管理框架的实现原理，下面我们来自己实现一个简单的绑定页面Activity的生命周期管理框架。

+ 定义对外调用类LifecycleDetector，单例模式获取类实例。

```
public class LifecycleDetector {

    static final String FRAGMENT_TAG = "com.bumptech.glide.manager";

    private static volatile LifecycleDetector sInstance;

    public static LifecycleDetector getInstance() {
        if (sInstance == null) {
            synchronized (LifecycleDetector.class) {
                if (sInstance == null) {
                    sInstance = new LifecycleDetector();
                }
            }
        }

        return sInstance;
    }

    public void observer(Activity activity, LifecycleListener lifecycleListener) {
        // 获取当前activity的FragmentManager
        android.app.FragmentManager fm = activity.getFragmentManager();
        // 注册无UI的fragment
        LifecycleManagerFragment current = getRequestManagerFragment(fm);

        current.getLifecycle().addListener(lifecycleListener);
    }


    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    LifecycleManagerFragment getRequestManagerFragment(final android.app.FragmentManager fm) {
        LifecycleManagerFragment current = (LifecycleManagerFragment) fm.findFragmentByTag(FRAGMENT_TAG);
        if (current == null) {
            if (current == null) {
                current = new LifecycleManagerFragment();
                fm.beginTransaction().add(current, FRAGMENT_TAG).commitAllowingStateLoss();
            }
        }
        return current;
    }
}
```

+ 定义接口Lifecycle和其实现类ActivityFragmentLifecycle

```
// 接口
public interface Lifecycle {
    void addListener(LifecycleListener listener);
}

// 实现类，保存fragment和Requestmanager映射关系的类，管理LifecycleListener
public class ActivityFragmentLifecycle implements Lifecycle {
    private final Set<LifecycleListener> lifecycleListeners =
            Collections.newSetFromMap(new WeakHashMap<LifecycleListener, Boolean>());
    private boolean isStarted;
    private boolean isDestroyed;


    @Override
    public void addListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);

        if (isDestroyed) {
            listener.onDestroy();
        } else if (isStarted) {
            listener.onStart();
        } else {
            listener.onStop();
        }
    }

    void onStart() {
        isStarted = true;
        for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
            lifecycleListener.onStart();
        }
    }

    void onStop() {
        isStarted = false;
        for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
            lifecycleListener.onStop();
        }
    }

    void onDestroy() {
        isDestroyed = true;
        for (LifecycleListener lifecycleListener : Util.getSnapshot(lifecycleListeners)) {
            lifecycleListener.onDestroy();
        }
    }
}
```

+ 定义空白Fragment（LifecycleManagerFragment）

```
public class LifecycleManagerFragment extends Fragment {

    private final ActivityFragmentLifecycle lifecycle;


    public LifecycleManagerFragment() {
        this(new ActivityFragmentLifecycle());
    }

    // For testing only.
    @SuppressLint("ValidFragment")
    LifecycleManagerFragment(ActivityFragmentLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public ActivityFragmentLifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void onStart() {
        super.onStart();
        lifecycle.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        lifecycle.onStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycle.onDestroy();
    }

}
```

+ 定义LifecycleListener

```
public interface LifecycleListener {

    /**
     * Callback for when {@link android.app.Fragment#onStart()}} or {@link android.app.Activity#onStart()} is called.
     */
    void onStart();

    /**
     * Callback for when {@link android.app.Fragment#onStop()}} or {@link android.app.Activity#onStop()}} is called.
     */
    void onStop();

    /**
     * Callback for when {@link android.app.Fragment#onDestroy()}} or {@link android.app.Activity#onDestroy()} is
     * called.
     */
    void onDestroy();
}
```

当以上框架所需的类定义好了之后，我们定义一个Test类实现LifecycleListener接口。然后在Activity页面中，比如onCreate方法中实现如下代码：

```
@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Test test = new Test();
        LifecycleDetector.getInstance().observer(this, test);
    }
```

之后，我们就可以在Test监听Activity页面的生命周期变化了。具体框架的一个类图如下所示：

![仿Glide生命周期框架](https://note.youdao.com/yws/public/resource/d77305433fb473583ca3af3cbe7f4b27/xmlnote/WEBRESOURCEe01870110ea7680f7440eb95901cfd3e/15447)

具体工程代码可以从这里获取：[CustomGlideLifecycleDemo](https://github.com/yushiwo/CustomGlideLifecycleDemo)

### 结束
至此，关于Glide如何绑定页面生命周期的原理讲解结束。在下一篇文章，将会介绍绑定页面生命周期的另一种方式，即基于Android Architecture Components框架的Lifecycle实现生命周期绑定，敬请期待。

### 参考
1. [Glide源码分析3 -- 绑定Activity生命周期](https://blog.csdn.net/u013510838/article/details/52143097)
