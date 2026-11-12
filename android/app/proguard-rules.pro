# WorkManager creates its Room database with Class.getDeclaredConstructor().
# R8 removed that constructor, so startup crashed with NoSuchMethodException: <init> [].
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.startup.Initializer { <init>(); }
