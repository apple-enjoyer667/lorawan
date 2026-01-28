plugins {
    id("java")
}

group = "org.mtgprod"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()

    maven {
        url = uri("https://mvnrepository.com/artifact")
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.mtgprod.Main"
    }
}

dependencies {
    implementation("com.fazecast:jSerialComm:2.11.4")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}