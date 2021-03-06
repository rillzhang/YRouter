package com.ysbing.yrouter.plugin

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.variant.VariantInfo
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.variant.BaseVariantData
import com.ysbing.yrouter.core.CheckClassObject
import com.ysbing.yrouter.core.util.FileOperation
import com.ysbing.yrouter.plugin.Constants.ANDROID_GRADLE_PLUGIN_VERSION
import com.ysbing.yrouter.plugin.Constants.YROUTER
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet


class CheckUsagesTransform(
    private val project: Project,
    private val android: AppExtension,
    private val isMock: Boolean
) : Transform() {

    override fun getName(): String {
        return "CheckUsagesTransform"
    }

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    override fun isIncremental(): Boolean {
        return false
    }

    override fun applyToVariant(variant: VariantInfo): Boolean {
        return !variant.isDebuggable || isMock
    }

    @Suppress("PrivateApi")
    override fun transform(transformInvocation: TransformInvocation) {
        super.transform(transformInvocation)
        transformInvocation.outputProvider.deleteAll()
        transformInvocation.context.temporaryDir.deleteRecursively()
        val variantName = transformInvocation.context.variantName
        val usagesInfo = HashMap<String, HashSet<String>>()
        android.applicationVariants.map { variant ->
            if (variant.name == variantName) {
                if (variant is ApplicationVariantImpl) {
                    val aars: ArtifactCollection =
                        if (ANDROID_GRADLE_PLUGIN_VERSION > "4.0.2") {
                            variant.variantData.variantDependencies.getArtifactCollection(
                                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                                AndroidArtifacts.ArtifactScope.ALL,
                                AndroidArtifacts.ArtifactType.EXPLODED_AAR
                            )
                        } else {
                            val scopeField =
                                BaseVariantData::class.java.getDeclaredField("scope")
                            scopeField.isAccessible = true
                            val variantDataMethod =
                                ApplicationVariantImpl::class.java.getDeclaredMethod("getVariantData")
                            val scope = scopeField.get(variantDataMethod.invoke(variant))
                            val getArtifactCollectionMethod =
                                VariantScope::class.java.getDeclaredMethod(
                                    "getArtifactCollection",
                                    AndroidArtifacts.ConsumedConfigType::class.java,
                                    AndroidArtifacts.ArtifactScope::class.java,
                                    AndroidArtifacts.ArtifactType::class.java
                                )
                            getArtifactCollectionMethod.invoke(
                                scope,
                                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                                AndroidArtifacts.ArtifactScope.ALL,
                                AndroidArtifacts.ArtifactType.EXPLODED_AAR
                            ) as ArtifactCollection
                        }
                    aars.artifacts.map { aar ->
                        val file = File(aar.file, FindUsagesTransform.INDEX_USAGES_FILE)
                        if (file.exists() && file.canRead()) {
                            println("找到AAR引用配置:$file")
                            usagesInfo.getOrPut("aar:$aar")
                            { HashSet() }.addAll(file.readLines())
                        }
                    }
                }
            }
        }
        fun findProject(name: String) {
            project.configurations.getAt(name).dependencies.map { depend ->
                if (depend is DefaultProjectDependency) {
                    val file = File(
                        depend.dependencyProject.buildDir,
                        "${YROUTER}${File.separator}${FindUsagesTransform.INDEX_USAGES_FILE}"
                    )
                    if (file.exists() && file.canRead()) {
                        println("找到工程引用配置:$file")
                        usagesInfo.getOrPut(":${depend.name}")
                        { HashSet() }.addAll(file.readLines())
                    }
                }
            }
        }
        findProject("api")
        findProject("implementation")
        val classInfo = checkUsages(usagesInfo)
        val extractFiles = ArrayList<File>()
        val extractClass = ArrayList<CheckClassObject.ClassInfoBean>()
        transformInvocation.inputs?.map {
            it.directoryInputs.map { dir ->
                val dest = transformInvocation.outputProvider.getContentLocation(
                    dir.name,
                    dir.contentTypes,
                    dir.scopes,
                    Format.DIRECTORY
                )
                dir.file.copyRecursively(dest, true)
                extractClass(
                    extractFiles,
                    extractClass,
                    classInfo,
                    dir.file,
                    transformInvocation.context.temporaryDir
                )
            }
            it.jarInputs.map { jar ->
                val dest = transformInvocation.outputProvider.getContentLocation(
                    jar.name,
                    jar.contentTypes,
                    jar.scopes,
                    Format.JAR
                )
                jar.file.copyTo(dest, true)
                extractClass(
                    extractFiles,
                    extractClass,
                    classInfo,
                    jar.file,
                    transformInvocation.context.temporaryDir
                )
            }
        }
        CheckClassObject.run(classInfo, extractFiles, extractClass)
    }

    private fun checkUsages(usagesInfo: HashMap<String, HashSet<String>>): TreeMap<CheckClassObject.ClassInfoBean, MutableList<String>> {
        val map = TreeMap<CheckClassObject.ClassInfoBean, MutableList<String>>()
        usagesInfo.keys.map { name: String ->
            val classLines = usagesInfo[name]
            classLines?.map add@{ usage ->
                if (usage.contains("$")) {
                    val classNameKey =
                        CheckClassObject.ClassInfoBean(name, usage.substringBefore(" :"))
                    if (!map.containsKey(classNameKey)) {
                        map[classNameKey] = ArrayList()
                    }
                } else if (!usage.contains(":")) {
                    val classNameKey = CheckClassObject.ClassInfoBean(name, usage)
                    if (!map.containsKey(classNameKey)) {
                        map[classNameKey] = ArrayList()
                    }
                } else if (usage.contains(":")) {
                    val className = usage.substringBeforeLast(":").substringBeforeLast(".")
                    val classNameKey = CheckClassObject.ClassInfoBean(name, className)
                    if (!map.containsKey(classNameKey)) {
                        map[classNameKey] = ArrayList()
                    }
                    map[classNameKey]?.add(usage)
                }
            }
        }
        val changeMap = HashMap<CheckClassObject.ClassInfoBean, String>()
        map.keys.map { className ->
            var hasKey: String? = null
            map.keys.map { key ->
                if (className != key && className.className.contains(key.className)) {
                    hasKey = key.className
                }
            }
            if (hasKey != null) {
                val newKey = "$hasKey\$${className.className.substringAfter("$hasKey.")}"
                changeMap[CheckClassObject.ClassInfoBean(className.moduleName, newKey)] =
                    className.className
            }
        }
        changeMap.map { change ->
            map.remove(CheckClassObject.ClassInfoBean(change.key.moduleName, change.value))?.let {
                map[change.key] = it
            }
        }
        return map
    }

    private fun extractClass(
        extractFiles: MutableList<File>,
        extractClass: MutableList<CheckClassObject.ClassInfoBean>,
        classInfo: TreeMap<CheckClassObject.ClassInfoBean, MutableList<String>>,
        file: File,
        tmpDir: File
    ) {
        val dir: File
        if (file.isFile) {
            dir = File(tmpDir, System.nanoTime().toString())
            FileOperation.unZipAPk(file.absolutePath, dir.absolutePath)
        } else {
            dir = file
        }
        classInfo.keys.map {
            val className = it.className.replace(".", File.separator) + ".class"
            val classFile = File(dir, className)
            if (classFile.exists()) {
                extractClass.add(it)
                extractFiles.add(classFile)
            }
        }
    }
}
