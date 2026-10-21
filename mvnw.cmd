@ECHO OFF
SET BASE_DIR=%~dp0
IF NOT "%JAVA_HOME%"=="" GOTO javaHome
SET JAVA_EXE=java.exe
GOTO run
:javaHome
SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
:run
"%JAVA_EXE%" %MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" -classpath "%BASE_DIR%.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
