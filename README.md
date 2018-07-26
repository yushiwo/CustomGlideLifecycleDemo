## 如何绑定页面生命周期（一）－Glide实现

>Glide中一个重要的特性，就是Request可以随着Activity或Fragment的onStart而resume，onStop而pause，onDestroy而clear。从而节约流量和内存，并且防止内存泄露，这一切都由Glide在内部实现了。用户唯一要注意的是，Glide.with()方法中尽量传入Activity或Fragment，而不是Application，不然没办法进行生命周期管理。

因为对Glide绑定生命周期的原理很感兴趣，所以看了一些源码解析的文章，也读了Glide的相关源码。发现大多数对于Glide生命周期绑定原理的介绍，是通过源码一步步的介绍。个人感觉这样没有重点，容易迷失在代码流程细节中。

所以这篇文章通过另外一种方式介绍Glide生命周期管理的原理，通过提问解答的方式，带着问题阅读，更加具有针对性。介绍主要分为两个部分：

+ Glide生命周期管理原理
+ 仿Glide自定义生命周期管理框架实践

### Glide生命周期管理原理
#### 实现生命周期绑定的原理

- 基于当前activity添加无UI的fragment，通过fragment接收activity传递的生命周期，并转发给RequestManager，实现生命周期感知。

#### 如何生成基于activity的fragment

- 基于传入的页面上下文，获取页面activity的fragmentmanager，生成无UI的fragment

#### 无UI的fragment如何将生命周期传递给RequestManager

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
