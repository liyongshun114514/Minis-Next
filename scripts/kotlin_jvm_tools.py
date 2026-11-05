"""Shared local Kotlin/JDK discovery for focused offline JVM tests."""
from pathlib import Path

DEFAULT_JDK = Path('/usr/lib/jvm/java-21-openjdk-arm64')
GRADLE_LIB = Path('/opt/gradle-8.11.1/lib')
KOTLIN_VERSION = '2.0.20'


def jdk_tools(jdk: Path) -> tuple[Path, Path]:
    java, javac = jdk / 'bin/java', jdk / 'bin/javac'
    for tool in (java, javac):
        if not tool.is_file():
            raise RuntimeError(f'Missing JDK tool: {tool}')
    return java, javac


def compiler_classpath() -> tuple[list[str], Path]:
    names = [
        f'kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar',
        f'kotlin-stdlib-{KOTLIN_VERSION}.jar',
        f'kotlin-script-runtime-{KOTLIN_VERSION}.jar',
        f'kotlin-reflect-{KOTLIN_VERSION}.jar',
        f'kotlin-daemon-embeddable-{KOTLIN_VERSION}.jar',
        'kotlinx-coroutines-core-jvm-1.6.4.jar',
        'trove4j-1.0.20200330.jar', 'annotations-24.0.1.jar',
    ]
    jars = [GRADLE_LIB / name for name in names]
    missing = [str(path) for path in jars if not path.is_file()]
    if missing:
        raise RuntimeError(f'Missing Kotlin compiler dependencies: {missing}')
    return list(map(str, jars)), GRADLE_LIB / f'kotlin-stdlib-{KOTLIN_VERSION}.jar'
