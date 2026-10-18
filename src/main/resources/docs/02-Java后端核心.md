# Java 后端高频考点速查

## Spring Boot 自动配置原理
Spring Boot 自动配置的核心是这样一个流程：启动类上的 @SpringBootApplication 注解是一个复合注解，它里面的 @EnableAutoConfiguration 通过 @Import 导入了一个 AutoConfigurationImportSelector 选择器。这个选择器会在启动时扫描所有 jar 包里的 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 文件，把这个文件里列出的一百多个自动配置类的全限定名读出来。这些配置类上通常带有 @ConditionalOnClass、@ConditionalOnMissingBean 之类的条件注解，只有当类路径下确实存在某个依赖、并且当前容器里还没有用户自定义的同类 Bean 时，这个自动配置才会生效。这也是为什么引入 spring-boot-starter-data-redis 之后，只要配了连接地址就能直接注入 RedisTemplate：因为 RedisAutoConfiguration 检测到类路径里有 Redis 依赖，并且你没有自己定义 RedisTemplate，它就帮你创建了一个默认的实现。理解这一点之后，排查自动配置不生效的问题就有方向了：先看依赖在不在，再看自己是不是定义了同名 Bean 覆盖了它，最后用 --debug 参数启动看 ConditionEvaluationReport 报告。

## MyBatis 一级缓存与二级缓存
一级缓存是 SqlSession 级别的缓存，默认开启。同一个 SqlSession 里执行两次完全相同的查询，第二次会直接从缓存返回而不发 SQL。一旦执行了增删改操作或者调用了 clearCache，这个缓存就会被清空，这是为了避免读到脏数据。

二级缓存是 mapper 命名空间级别的缓存，作用域更大，可以被多个 SqlSession 共享，默认关闭。要使用二级缓存需要在 mapper.xml 里加 cache 标签，并且实体类要实现 Serializable 接口。它的坑在于，如果对某个表有增删改操作，同一命名空间下的缓存会被刷新，但如果是跨命名空间的关联操作就不一定了，容易产生脏数据。所以生产环境里二级缓存用得很少，大多数团队宁愿在业务层用 Redis 自己控。

## JVM 内存区域划分
JVM 运行时数据区分为线程共享区和线程私有区两部分。线程共享的有堆和方法区。堆是最大的一块，几乎所有对象和数组都在这里分配，也是垃圾回收的主战场。方法区在 JDK 8 之后由元空间实现，使用的是本地内存而不是 JVM 内存，存放类的元信息、常量池、静态变量和即时编译器编译后的代码。线程私有的有虚拟机栈、本地方法栈和程序计数器。虚拟机栈描述的是 Java 方法执行的内存模型，每个方法调用都会创建一个栈帧，里面存局部变量表、操作数栈、动态链接和方法返回地址，StackOverflowError 就是线程请求的栈深度超过了虚拟机允许的深度。程序计数器是当前线程所执行字节码的行号指示器，它是唯一一个不会出现 OutOfMemoryError 的区域。

## 线程池核心参数
ThreadPoolExecutor 有七个核心参数，面试常问的是其中几个。corePoolSize 是核心线程数，线程池里长期保有的线程数量。maximumPoolSize 是最大线程数，当工作队列排满了之后线程池最多还能再创建多少线程。keepAliveTime 和 unit 描述的是非核心线程在闲置多久之后会被回收。workQueue 是任务阻塞队列，用来存放还没来得及执行的任务。threadFactory 是创建线程的工厂，通常在这里给线程起个有意义的名字，排查线上问题的时候特别有用，看到一堆 pool-1-thread-1 根本不知道是谁的线程。handler 是拒绝策略，当线程数达到最大值并且队列也排满时触发，内置的四种策略分别是直接抛异常的 AbortPolicy、由调用者线程自己执行的 CallerRunsPolicy、丢弃最老任务的 DiscardOldestPolicy 和直接丢弃不抛异常的 DiscardPolicy。

线程池的工作顺序是这样的：提交任务后，如果当前线程数小于核心线程数就创建新线程执行；如果已经达到核心线程数就把任务丢进队列；如果队列也满了就创建非核心线程；如果线程数已经到最大值仍处理不过来，就执行拒绝策略。这个顺序要记牢。

## MySQL 索引失效的常见场景
索引本身是有序的，一旦查询方式破坏了有序性或者让优化器没法用索引定位，就会退化成全表扫描。常见的失效场景包括：在索引列上做函数运算或者表达式计算，比如 where year(create_time) = 2026；使用左模糊或者全模糊匹配，比如 like '%abc'，因为不知道从哪个字符开始匹配，违反了最左前缀原则；发生了隐式类型转换，比如索引列是字符串类型但查询时传了数字，MySQL 会自动给列加转换函数导致失效；在联合索引里没有遵循最左前缀原则，跳过了前面的列直接用后面的列做条件；使用了范围查询之后的列，比如联合索引 a,b,c 里 a 用了范围查询，那么 c 就用不上索引了。另外要注意，即使字段上有索引，如果优化器判断回表代价太高，比如查询命中了全表百分之三十以上的数据，它也会主动放弃索引改走全表扫描。
