package com.geniatech.el133sdk;


/**
 * 定义服务接口
 */
interface EpdManager {
    // 获取EPD信息
    String getEPDInfo();

    // 发送图像到TCON显示
    int sendImage(String imagePath);

    // 发送图像到TCON显示,多个屏
    int sendImageByNum(String imagePath,int num);

    // 带特效发送图像到TCON显示
    int sendImageAddMagic(String imagePath,String magicPath);

    // 发送图像到TCON显示
    int sendImageBitmap(in android.graphics.Bitmap bitmap);

    // 发送图像细节到TCON显示
    int sendImageWithDetails(String imagePath, int x, int y, int w, int h,double hue_offset,
                         double sat_offset,double bright_offset,double contrast_offset,double gamma);

    // 设置设备睡眠
    void goToSleep(int sleepSec);

    // 重启设备
    void setOSReboot();

     // 重启设备时间
    void setOSRebootTime(int rebootTime);

    // 获取TCON温度
    int getTCONTemperature();

    // 升级TCON
    void upgradeTCON(String path);

    // 获取服务版本
    String getServiceVersion();

    // 设置EPD屏幕旋转
    void setEPDScreenRotate(int degree);

    // 获取EPD屏幕旋转
    int getEPDScreenRotate();

   // 设置LED灯
    void setLedOn(int brightness);
    void setLedOff();

    // 开关Wi-Fi
    void setWifiOn();
    void setWifiOff();
    void forgetWifi();
    String getWifiCountryCode();
    void setWifiCountryCode(String countryCode);

    // 开关Hotspot
    void setHotspotOn();
    void setHotspotOff();

    // 设备序列号和版本号
    String getSerialNumber();
    String getBuildNumber();

    // NTP Server
    void setNTPServer(String ntpServer);
    String getNTPServer();

    // 设置时区
    void setTimeZone(String timeZone);

    // 设置系统时间
    void setSystemTime(int year, int month, int day, int hour, int minute, int second);

    // 安装APK
    void installAPK(String path, String apkName, String pkgName);

    //是否显示导航栏
    void isShowNavigationBar(boolean isshow);

    //是否显示状态栏
    void isShowStatusBar(boolean isshow);

    //局部刷新函数
    int sendpartImage(String imagePath, int x, int y);

   // 带特效局部刷新TCON显示
    int sendpartImageAddMagic(String imagePath,String magicPath,int x,int y);

    // 掩码图局部刷新TCON显示
    int sendpartImageWithMask(String imagePath,String MaskImagePath);

    // 掩码图带特效局部刷新TCON显示
    int sendpartImageWithMaskAddMagic(String imagePath,String MaskImagePath,String magicPath);

    //局部刷新函数
    int sendpartImageBitmap(in android.graphics.Bitmap bitmap, int x, int y);

    //截图
    int screenshot(String outputImgPath);

    //设置自动刷新时间
    void setAutoRefrushTime(int time);

    //自动刷新开关
    void isOpenRefrushTime(boolean isopen);

    //更新系统固件
    void fwUpgrade(String path);

    //清屏
    void clScr();

    //设置刷图模式
    void setDisplayMode(int mode);

    // 发送视频流到TCON显示
    int sendStream(String streamPath);

    // 获取电池电量
    int getBatteryLevel();

    // 设置EPD是否启用Rippe模式
    void setEPDRippeMode(boolean isRippe);

    // 获取EPD是否启用Rippe模式
   boolean getEPDRippeMode();

    // 设置刷图参数
    void setImageAdjustment(double hue_offset, double sat_offset, double bright_offset, double contrast_offset, double gamma);
}
