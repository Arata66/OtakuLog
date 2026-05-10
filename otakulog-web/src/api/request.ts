import axios from 'axios'
import type { ApiResponse } from './types'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 响应拦截器：解包 ApiResponse<T>，直接返回 data
request.interceptors.response.use(
  (response): any => {
    const res = response.data as ApiResponse<unknown>
    if (res.code === 200) {
      return res.data
    }
    // 业务错误：抛出含 message 的 Error
    const err = new Error(res.message || '请求失败')
    ;(err as any).code = res.code
    return Promise.reject(err)
  },
  (error) => {
    // HTTP 错误
    if (error.response?.data) {
      const res = error.response.data as ApiResponse<unknown>
      const err = new Error(res.message || `HTTP ${error.response.status}`)
      ;(err as any).code = res.code || error.response.status
      return Promise.reject(err)
    }
    return Promise.reject(error)
  },
)

export default request
