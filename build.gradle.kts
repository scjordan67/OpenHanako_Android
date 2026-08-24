// 根项目**不声明任何插件** —— 包括 `apply false`。
//
// 这不是省事，是被两条约束夹出来的唯一解：
//
// 1. 开发容器连不上 dl.google.com（出口策略挡了 CONNECT），而 AGP 只发布在
//    Google 的仓库。根项目里哪怕只写 `alias(libs.plugins.android.application)
//    apply false`，Gradle 也会去解析这个坐标，于是连 :core 的构建都被拖垮。
//    所以 AGP 绝不能出现在根项目。
//
// 2. 但只把 AGP 留给 android/build.gradle.kts、同时让根项目声明 kotlin.jvm，
//    会踩到 classloader 的父子关系：KGP 被根项目的 buildscript classloader
//    装载，AGP 被 :android 子项目的 classloader 装载，而后者是前者的**子**。
//    父加载器看不见子加载器里的类，于是 KotlinAndroidTarget 找不到 AGP 的
//    BaseVariant，报 NoClassDefFoundError。（CI 上真实撞到过。）
//
// 两条合起来的结论：AGP 与 KGP 必须在同一个 classloader 里，而那个 classloader
// 不能是根项目的。所以让每个子项目各自声明自己完整的插件集 ——
// :core 装 kotlin.jvm + serialization，:android 装 AGP + kotlin.android +
// compose，各自一个 classloader，互不牵连。版本仍由 gradle/libs.versions.toml
// 统一，不会漂移。
//
// 代价只是同一份 KGP 可能被装载两次。两个模块的构建，可以忽略。
