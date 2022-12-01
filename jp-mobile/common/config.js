let APP_SERVER_URL = ""

if(process.env.NODE_ENV === 'development'){
    // 开发环境
    APP_SERVER_URL = 'api'
}else{
    // 生产环境
    APP_SERVER_URL = 'http://demo1.jeeplus.org/jeeplus-vue'
}

APP_SERVER_URL = APP_SERVER_URL + "/app"

export default APP_SERVER_URL