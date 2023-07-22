# mydianping
点评项目，使用时需要新建application.yaml(本人开发时使用云服务器，为了不暴露密码没有添加该文件)

##创建application.yaml
在src/main/resources目录下创建application.yaml文件，并拷贝以下内容
```
server:
     port: 8081
   spring:
     application:
       name: hmdp
     datasource:
       driver-class-name: com.mysql.cj.jdbc.Driver
       #mysql服务器ip
       url: jdbc:mysql://?characterEncoding=UTF8&autoReconnect=true&useSSL=true
       username: root
       #mysql服务器密码
       password: 
     redis:
       #redis服务器ip
       host: 
       port: 6379
       #redis服务器密码
       password: 
       database: 0
       lettuce:
         pool:
           max-active: 10
           max-idle: 10
           min-idle: 1
           time-between-eviction-runs: 10s
     jackson:
       default-property-inclusion: non_null # JSON处理时忽略非空字段
   mybatis-plus:
     type-aliases-package: com.hmdp.entity # 别名扫描包
   logging:
     level:
       com.hmdp: debug```