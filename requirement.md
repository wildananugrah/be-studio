I wanna make a backend app where it should be dynamic for transforming json request message and json response message. 
the configurable should be in database

the app has a capability to:
1. transform the request and response message
2. invoke to another app via REST API
3. validate json schema
4. custom clasz just in case we need to handle manually

for the json class there are two types
1. handle for spesifc field
2. handl for header and body 

tech stack:
1. using java spring boot
2. database postgres for development purpose. However it should be configurable to oracle database

the app should load the config when it starts up. 