# 嵌入式 Linux 面试复习：启动流程、根文件系统与驱动

Oct 25, 2026 · @Harvey

## Q1：/sys、/dev、/proc 是什么？是文件吗？存在哪里？

**一句话结论：** 它们是挂载在空目录上的虚拟文件系统，内容由内核在内存中动态生成，不占 flash，断电消失。

**面试回答：**

根文件系统里只有 /sys、/dev、/proc 这几个空目录。开机时内核或 rcS 脚本把 sysfs、devtmpfs、procfs 挂载上去，里面才有内容。它们能用 ls、cat、echo 操作，但本质是内核提供的接口。

| 目录 | 文件系统 | 作用 | 例子 |
| --- | --- | --- | --- |
| /dev | devtmpfs | 设备节点，应用访问驱动的入口；节点不存数据，只存类型（c/b）和主次设备号 | /dev/ttymxc0 = 207,16 |
| /sys | sysfs | 内核设备模型的导出，按总线、设备、驱动、类别组织，读写属性即可查看和控制设备 | /sys/class/leds/xxx/brightness |
| /proc | procfs | 进程和内核运行信息 | /proc/cmdline、/proc/meminfo |

- 主设备号：内核用它找到对应的驱动（mem 驱动是 1，i.MX 串口驱动是 207）。
- 次设备号：驱动内部区分具体设备（ttymxc0 是 16，ttymxc1 是 17；i.MX 驱动源码 MINOR\_START = 16）。
- 节点名和设备号都由驱动决定，所以 i.MX 串口叫 ttymxc0，不叫 ttyS0。
- /sys 与 /dev 的关系：device\_create 会在 /sys/class/<类>/<设备>/dev 写入 207:16，devtmpfs 或 mdev 据此在 /dev 创建节点。

**动手验证：** `mount | grep -E "sysfs|devtmpfs|proc"`；`ls -l /dev/ttymxc0`；`cat /sys/class/tty/ttymxc0/dev`。

**可能的追问：**

- 字符设备和块设备的区别？字符设备按字节流顺序访问（串口、I2C）；块设备按块随机访问并经过缓存（eMMC、SD）。
- 设备号怎么分配？register\_chrdev\_region 静态指定，alloc\_chrdev\_region 动态申请，推荐后者。

## Q2：介绍嵌入式 Linux 根文件系统的目录结构

**一句话结论：** 按启动时被用到的顺序讲最清楚：先是第一个进程，然后是配置，接着是虚拟目录，最后是命令、库和数据。

**面试回答：**

| 用到的时机 | 目录 | 作用 |
| --- | --- | --- |
| 内核挂载完 / 后 | /linuxrc、/sbin/init | PID 1 的第一个进程；BusyBox 中是指向 busybox 的软链接 |
| init 启动后 | /etc | inittab、init.d/rcS、fstab、profile 等配置 |
| rcS 执行时 | /proc、/sys、/dev | 空目录，挂载为虚拟文件系统 |
| 运行命令时 | /bin、/sbin、/usr/bin | 命令；/sbin 为系统管理命令（ifconfig、insmod、reboot） |
| 程序加载时 | /lib、/usr/lib | 动态库、动态链接器、内核模块 /lib/modules；必须来自同一交叉编译工具链 |
| 运行中 | /tmp、/var | 临时文件、日志 |
| 按需使用 | /mnt、/opt、/root | 临时挂载点、第三方或自己的应用、root 家目录 |

- 内核 zImage 和设备树 dtb **不在**根文件系统中，由 U-Boot 单独加载。
- /root 是 root 用户的家目录，不是根目录 /。

**可能的追问：**

- 根文件系统怎么制作？用 BusyBox 编译基础命令，补充 /lib 库、/etc 配置、/dev 等目录；或用 Buildroot 一键生成。
- 开发阶段为什么用 NFS 挂载根文件系统？主机编译后板子直接运行，不用反复烧写。

## Q3：ARM Linux 开发板的启动流程

**一句话结论：** 芯片带起 U-Boot，U-Boot 带起内核，内核挂载根文件系统并启动 init，init 带起其余所有程序。

**面试回答（以 i.MX6ULL 为例）：**

1. **BootROM**：固化在芯片内部。读取 BOOT\_MODE 引脚和 eFuse 确定启动介质，按镜像头的 DCD 表初始化 DDR，把 U-Boot 加载到 DDR 运行。
2. **U-Boot**：初始化时钟、串口、网卡；执行 bootcmd，从 eMMC 或 tftp 把 zImage 和 dtb 加载到内存，用 bootz 启动内核并传递 bootargs。
3. **内核**：解压后解析设备树，初始化子系统，按 compatible 匹配驱动并执行 probe；按 root= 挂载根文件系统，最后执行 init（PID 1）。
4. **init**：BusyBox init 读取 /etc/inittab，执行 rcS：挂载 proc、sys，mdev -s 填充 /dev，配置网络，启动应用。
5. **用户空间**：在串口 ttymxc0 上启动 shell，应用程序运行。

**根据报错定位卡在哪一步：**

| 报错 | 卡在哪 | 先查什么 |
| --- | --- | --- |
| Kernel panic: VFS: Unable to mount root fs | 第 3 步，挂载 / 失败 | 网络是否通、NFS 服务是否运行、/etc/exports、nfsroot 路径、nfsvers=3 |
| Kernel panic: No working init found | 第 3 步末，挂上了但找不到 init | /linuxrc、/sbin/init、busybox 本身；可用 init= 手动指定 |
| 程序运行报 not found | 第 5 步，程序依赖缺失 | /lib 中的动态库是否来自同一交叉编译工具链 |

**可能的追问：**

- 设备树的作用？把硬件描述从内核代码中分离出来，同一个内核可以适配不同板子。
- bootargs 常用参数？console、root、rootfstype、init、nfsroot、ip。
- 项目里怎么用？开发时 tftp 加载内核、NFS 挂载根文件系统，修改后不用反复烧写。

## Q4：platform 驱动从设备树到 /dev 节点的完整过程

**一句话结论：** 设备树节点变成 platform\_device，驱动注册 platform\_driver，两者在 platform 总线上按 compatible 匹配后调用 probe；probe 中注册字符设备并调用 device\_create，在 /sys 留下设备号，devtmpfs 或 mdev 再据此创建 /dev 节点。

&#91;embedded content: platform 驱动：设备树到 /dev 节点 · 8 步\]

左边是设备的来源，右边是驱动的来源，两者只在总线匹配这一步相遇；cdev\_add 之后驱动已可用，但 /dev 节点要等 device\_create 之后才出现。

**面试回答（按时间顺序）：**

1. **设备树描述硬件**：在 .dts 中写节点，包含 compatible、reg、interrupts、gpios、status = "okay"，编译成 .dtb 由 U-Boot 传给内核。
2. **内核展开设备树**：启动时把 dtb 解析成 device\_node 树，再由 of\_platform\_populate 为符合条件的节点创建 platform\_device，挂到 platform 总线上。
3. **驱动注册**：驱动定义 platform\_driver，在 of\_match\_table 中写相同的 compatible，用 module\_platform\_driver 或 platform\_driver\_register 注册到 platform 总线。
4. **总线匹配**：platform 总线的 match 函数比较两边的 compatible（优先级：of\_match\_table > ACPI > id\_table > name），匹配成功就调用驱动的 probe。
5. **probe 获取资源**：从 pdev->dev.of\_node 读取属性，platform\_get\_resource 拿寄存器地址并 devm\_ioremap，获取 GPIO、中断等。
6. **注册字符设备**：alloc\_chrdev\_region 申请设备号 → cdev\_init 绑定 file\_operations → cdev\_add 加入内核。此时内核已能用设备号找到驱动，但还没有 /dev 节点。
7. **创建设备节点**：class\_create 在 /sys/class 下建类；device\_create 在 /sys/class/<类>/<设备>/ 下生成 dev 文件（内容如 240:0），并发出 uevent。
8. **/dev 节点出现**：devtmpfs 由内核直接创建 /dev/<设备>；BusyBox 系统中 mdev 也会根据 /sys 和 uevent 创建或补充节点。
9. **应用访问**：应用 open("/dev/xxx")，VFS 读出节点的设备号，找到对应的 cdev，调用驱动 file\_operations 中的 open/read/write/ioctl。

**卸载时反过来：** device\_destroy → class\_destroy → cdev\_del → unregister\_chrdev\_region，顺序错了会残留节点或导致内核崩溃。

**可能的追问：**

- 为什么要用 platform 总线？SoC 内部外设不挂在 I2C、USB 这类物理总线上，platform 是虚拟总线，实现设备与驱动分离。
- probe 没有被调用怎么排查？检查 compatible 是否完全一致、节点 status 是否为 okay、/sys/bus/platform/devices 下是否有设备、驱动是否已加载。
- 为什么用 devm\_ 系列函数？资源跟随设备生命周期自动释放，probe 出错或 remove 时不用手动回收。

## Q5：什么是字符设备？为什么要注册它？说说字符设备驱动的完整流程

**一句话结论：** Linux 设备分为字符、块、网络三类；字符设备按字节流访问，注册的本质是向内核登记"设备号 → file\_operations"，让应用能通过 open/read/write 安全地操作硬件。

**1. 三类设备的区别**

| 类型 | 访问方式 | /dev 节点 | 典型设备 |
| --- | --- | --- | --- |
| 字符设备 | 字节流顺序读写，不经过缓存 | 有，类型 c | 串口、LED、按键、ADC、I2C/SPI 用户接口 |
| 块设备 | 按块随机访问，经过页缓存和 I/O 调度，可建文件系统 | 有，类型 b | eMMC、SD 卡、U 盘、mtdblock |
| 网络设备 | 通过 socket 收发数据包，经协议栈 | 没有，只有接口名 eth0 | 网卡、Wi-Fi、SocketCAN |

**2. 为什么要注册字符设备**

- 应用运行在用户态，受 MMU 和特权级隔离，不能直接访问寄存器或调用内核函数，需要内核提供受控通道。
- Linux 按"一切皆文件"把驱动包装成文件，应用用标准的 open/read/write/ioctl 操作。
- 注册就是登记"设备号 → fops"的对应关系；不注册，驱动在内核里但应用找不到入口。
- 自定义外设既不是存储也不是网卡，所以通常归为字符设备。已有子系统（input、tty、i2c-dev）会代为注册；简单设备可用 misc\_register，主设备号固定为 10。

**3. 驱动流程**

| 阶段 | 函数 | 作用 |
| --- | --- | --- |
| 实现操作集 | file\_operations：open、read、write、release、unlocked\_ioctl | 定义应用调用时驱动做什么 |
| ① 申请设备号 | alloc\_chrdev\_region(&devid, 0, 1, "led") | 动态获取主次设备号，MAJOR()/MINOR() 拆分；会出现在 /proc/devices |
| ② 初始化 cdev | cdev\_init(&cdev, &fops) | 把 cdev 与 fops 绑定 |
| ③ 加入内核 | cdev\_add(&cdev, devid, 1) | 完成"设备号 → fops"登记，驱动此时已可用 |
| ④ 创建类 | class\_create | 生成 /sys/class/<类> |
| ⑤ 创建设备 | device\_create | 生成 /sys/class/<类>/<设备>/dev，进而自动出现 /dev 节点 |
| ⑥ 初始化硬件 | ioremap、GPIO、中断申请 | platform 驱动中在 probe 里从设备树获取资源 |
| 卸载 | device\_destroy → class\_destroy → cdev\_del → unregister\_chrdev\_region | 倒序释放；注册过程中出错用 goto 逐级回退 |

```c
static ssize_t led_write(struct file *filp, const char __user *buf,
                         size_t cnt, loff_t *off)
{
    if (copy_from_user(kbuf, buf, cnt))   /* 用户空间 → 内核空间 */
        return -EFAULT;
    /* 根据 kbuf 操作硬件 */
    return cnt;
}
static const struct file_operations led_fops = {
    .owner = THIS_MODULE, .open = led_open,
    .write = led_write,   .release = led_release,
};
```

**4. 应用访问时的调用路径**

open("/dev/led") → VFS 读取节点中的设备号 → 找到对应 cdev → 调用 fops.open；write(fd, …) → fops.write → copy\_from\_user → 操作寄存器。

**5. 关键理解：节点不是必需的，设备号才是**

只执行 ①②③ 时 /dev/led 不存在，但驱动已经可用：从 /proc/devices 查到主设备号（例如 240），执行 `mknod /dev/led c 240 0` 即可访问。④⑤ 的作用是自动创建节点。

**新旧写法：** 旧的 register\_chrdev(major, name, &fops) 一步完成注册，但占用该主设备号下全部 256 个次设备号，也不自动创建节点，需手动 mknod；现在推荐 alloc\_chrdev\_region + cdev + device\_create。

**可能的追问：**

- 为什么要用 copy\_to\_user/copy\_from\_user，不能直接访问用户指针？用户地址可能无效或未映射到物理页，这两个函数会做检查并安全处理缺页。
- read/write 和 ioctl 怎么分工？read/write 传数据流，ioctl 传控制命令（如设置波特率、模式）。
- 一个驱动怎么管理多个同类设备？申请多个次设备号，在 open 中用 MINOR(inode->i\_rdev) 或 container\_of 找到对应设备的私有结构，存入 filp->private\_data。

## 速记：四题串成一条线

面试前过一遍这五行，每行都能展开成一题：

1. **启动**：BootROM → U-Boot（zImage + dtb + bootargs）→ 内核 → init（inittab、rcS）→ shell 和应用。
2. **内核阶段**：解析设备树 → 创建 platform\_device → 匹配驱动 → probe。
3. **probe**：alloc\_chrdev\_region → cdev\_add → class\_create → device\_create。
4. **节点**：/sys/class/.../dev 记录设备号 → devtmpfs 或 mdev 创建 /dev 节点；节点只存 c/b + 主次设备号。
5. **根文件系统**：/linuxrc → /etc → /proc /sys /dev → /bin /sbin → /lib；内核和 dtb 不在其中。

**排错口诀：** 挂不上看网络和 NFS，找不到 init 看 linuxrc，命令 not found 看 /lib，probe 不调用看 compatible 和 status。
