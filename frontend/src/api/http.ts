/**
 * Axios HTTP 客户端封装
 *
 * 统一配置 baseURL、超时、请求/响应拦截器。
 * 生产环境和开发环境都使用相对路径，
 * 开发时由 Vite proxy 转发，生产时由 Spring Boot 处理。
 */
import axios from 'axios'

/** 创建 axios 实例，统一配置超时和默认头 */
const http = axios.create({
  baseURL: '/',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

/**
 * 请求拦截器：可在此处注入 token 等鉴权信息
 */
http.interceptors.request.use(
  (config) => {
    // 如果后续需要登录鉴权，在此注入 Authorization header
    return config
  },
  (error) => Promise.reject(error),
)

/**
 * 响应拦截器：统一处理错误响应
 */
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const msg =
      error.response?.data?.message ||
      error.response?.data?.error ||
      error.message ||
      '请求失败'
    return Promise.reject(new Error(msg))
  },
)

export default http
