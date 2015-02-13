.PHONY: all clean

all: TMCRS.jar

clean:
	rm -rf bin TMCRS.jar .src.lst

TMCRS.jar: $(shell find src)
	rm -rf bin TMCRS.jar
	cp -r src bin
	find src -name *.java >.src.lst
	javac -source 1.7 -target 1.7 -d bin @.src.lst
	mkdir -p bin/META-INF
	echo 'Version: 1.0' >bin/META-INF/MANIFEST.MF
	echo 'Main-Class: togos.minecraft.regionshifter.RegionShifter' >>bin/META-INF/MANIFEST.MF
	cd bin ; zip -9 -r ../TMCRS.jar . ; cd ..
