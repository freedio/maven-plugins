A maven plugin for creating Comprehensive ARchives (CAR files).

A Comprehensive ARchive comes in two flavors, depending on whether it is created as a library CAR or an executable CAR.

A library CAR contains just the library code (as in normal JAR files) along with a customized MANIFEST listing its dependencies and the classes that either contain dependency injection points and thus need implementations, or classes that provide such an implementation.

An executable CAR (also called Uber-JAR) contains a JAR boot loader (CarLoader), a class loader (CarClassLoader) capable of loading classes from the nested JAR (with the CAR at its base), a customized MANIFEST, and the dependencies in the form of JAR files.  The actual executable project is first packaged into a JAR and then added as a dependency containing the application class with the static main method that will be invoked from the boot loader.

The CarClassLoader also acts as a CDI resolver, using its friend, CarInjector, to do the actual CDI work.

Both the maven plugin and the executable CAR are based on the Coradec infrastructure projects available as com.coradec:coradeck
