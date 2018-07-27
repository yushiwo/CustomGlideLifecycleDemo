## 如何绑定页面生命周期（一）－Glide实现

>Glide中一个重要的特性，就是Request可以随着Activity或Fragment的onStart而resume，onStop而pause，onDestroy而clear。从而节约流量和内存，并且防止内存泄露，这一切都由Glide在内部实现了。用户唯一要注意的是，Glide.with()方法中尽量传入Activity或Fragment，而不是Application，不然没办法进行生命周期管理。

因为对Glide绑定生命周期的原理很感兴趣，所以看了一些源码解析的文章，也读了Glide的相关源码。发现大多数对于Glide生命周期绑定原理的介绍，是直接通过源码一步步的介绍。个人感觉这样没有重点，容易迷失在代码流程细节中。

所以这篇文章通过另外一种方式介绍Glide生命周期管理的原理，通过提问解答的方式，带着问题阅读，更加具有针对性。介绍完了原理之后，我们通过基于Glide生命周期感知的原理，实现了一个测试demo，进一步加深巩固之前所学知识点。所以，介绍主要分为两个部分：

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

1. 如何生成基于当前传入Activity无UI的Fragment，即如何实现对页面的周期绑定。
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
无UI的fragment如何将生命周期传递给RequestManager

- 定义lifecyclelistener接口，内部主要定义了一些生命周期回调方法
- requestmanager实现lifecyclelistener接口
- 新建当前activity的requestmanager对象，构造函数传入参数中有一个ActivityFragmentLifecycle对象引用，其内部有一个lifecycleListeners的set集合，存储lifecyclelistener的子类对象引用。
- 在requestmanager构造函数中，将当前的requestmanager对象存入ActivityFragmentLifecycle 中的lifecycleListeners集合。此时在fragment中已经可以通过ActivityFragmentLifecycle操作requestmanager了
- 在fragment的生命周期回调方法中，回调ActivityFragmentLifecycle中每个lifecyclelistener的对应生命周期回调方法，从而回调到requestmanager中的生命周期方法。
- 最后，在requestmanager的生命周期回调方法中，可以对图片相关请求进行相应的处理了。

![Glide中生命周期传递](http://on-img.com/chart_image/5b55438ce4b053a09c10a29d.png)


### 几个关键类和关系

#### 关键类
+ 对外调用的类：Glide 
+ 一个处理中间类：RequestManagerRetriever
+ 生命周期感知的fragment：RequestManagerFragment
+ 具体处理图片请求的管理类：RequestManager
+ 定义生命周期回调方法的接口：LifecycleListener
+ 保存fm和requestmanager映射关系的类：ActivityFragmentLifecycle

#### 关键关系
+ RequestManager实现LifecycleListener，实现其生命周期相关接口，具体处理图片相关网络请求
+  ActivityFragmentLifecycle作为RequestManagerFragment和RequestManager的成员，在RequestManagerFragment中初始化，通过RequestManager构造函数传入RequestManager内部，由ActivityFragmentLifecycle内部的set集合去持有当前RequestManager对象引用
+  activity生命周期可以在RequestManagerFragment中感知，通过在RequestManagerFragment的生命周期回调方法中，调用ActivityFragmentLifecycle对象的方法，最终便利调用set集合中对象的生命周期对应方法，实现生命周期感知的处理。

### 生命周期管理框架实践

### 参考
1. [Glide源码分析3 -- 绑定Activity生命周期](https://blog.csdn.net/u013510838/article/details/52143097)
