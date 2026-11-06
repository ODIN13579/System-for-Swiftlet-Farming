
import { useState, type FormEvent } from 'react'
import { Bird, LockKeyhole, Mail } from 'lucide-react'
import { api } from '../services/api'

type LoginProps = {
  onLogin: (token: string) => void
}

export default function Login({ onLogin }: LoginProps) {
  const [email, setEmail] = useState('local@example.test')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setLoading(true)

    try {
      const result = await api.login(email.trim(), password)

      if (!result.accessToken) {
        throw new Error('Backend không trả về access token.')
      }

      onLogin(result.accessToken)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Đăng nhập thất bại. Vui lòng thử lại.',
      )
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="login-page">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-brand-icon">
          <Bird size={34} />
        </div>

        <h1>SwiftletCare</h1>
        <p className="login-description">
          Đăng nhập để quản lý nhà yến thông minh
        </p>

        <label htmlFor="login-email">Email</label>
        <div className="login-input">
          <Mail size={18} />
          <input
            id="login-email"
            type="email"
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="Nhập email"
            required
          />
        </div>

        <label htmlFor="login-password">Mật khẩu</label>
        <div className="login-input">
          <LockKeyhole size={18} />
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="Nhập mật khẩu"
            required
          />
        </div>

        {error && (
          <div className="login-error" role="alert">
            {error}
          </div>
        )}

        <button className="login-submit" type="submit" disabled={loading}>
          {loading ? 'Đang đăng nhập...' : 'Đăng nhập'}
        </button>

        <p className="login-footer">
          Hệ thống giám sát và điều khiển nhà yến
        </p>
      </form>
    </main>
  )
}
