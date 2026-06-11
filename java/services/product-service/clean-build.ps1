cd "C:\Users\USER\E-Commerce Platform\java\services\product-service"
Remove-Item -Recurse -Force target -ErrorAction SilentlyContinue
$env:JAVA_HOME = "C:\Program Files\Java\jdk-24"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "C:\Users\USER\.m2\wrapper\dists\apache-maven-3.9.11\a2d47e15\bin\mvn.cmd" clean compile 2>&1
