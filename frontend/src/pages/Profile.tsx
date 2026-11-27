import { endSession } from '../services/api'

import { useEffect, useState, type FormEvent } from 'react'
import { LockKeyhole, Save, UserRound, ShieldCheck } from 'lucide-react'
import { api, type Account } from '../services/api'

export default function Profile() {
  const [account, setAccount] = useState<Account | null>(null)
  const [fullName, setFullName] = useState('')
  const [phone, setPhone] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(true)
  const [savingProfile, setSavingProfile] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)
  const [profileMessage, setProfileMessage] = useState('')
  const [passwordMessage, setPasswordMessage] = useState('')
  const [profileError, setProfileError] = useState('')
  const [passwordError, setPasswordError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function loadProfile() {
      try {
        const result = await api.getMe()

        if (cancelled) return

        setAccount(result)
        setFullName(result.fullName ?? '')
        setPhone(result.phone ?? '')
      } catch (error) {
        if (!cancelled) {
          setProfileError(
            error instanceof Error
              ? error.message
              : 'Không thể tải hồ sơ cá nhân.',
          )
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void loadProfile()

    return () => {
      cancelled = true
    }
  }, [])

  async function handleSaveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setProfileMessage('')
    setProfileError('')
    setSavingProfile(true)

    try {
      const updated = await api.updateMe({
        fullName: fullName.trim() || null,
        phone: phone.trim() || null,
      })

      setAccount(updated)
      setFullName(updated.fullName ?? '')
      setPhone(updated.phone ?? '')
      setProfileMessage('Đã cập nhật hồ sơ thành công.')
    } catch (error) {
      setProfileError(
        error instanceof Error
          ? error.message
          : 'Không thể cập nhật hồ sơ.',
      )
    } finally {
      setSavingProfile(false)
    }
  }

  async function handleChangePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setPasswordMessage('')
    setPasswordError('')

    if (newPassword.length < 12) {
      setPasswordError('Mật khẩu mới phải có ít nhất 12 ký tự.')
      return
    }

    if (!/[A-Za-z]/.test(newPassword) || !/[0-9]/.test(newPassword)) {
      setPasswordError('Mật khẩu mới phải có cả chữ cái và chữ số.')
      return
    }

    if (newPassword !== confirmPassword) {
      setPasswordError('Mật khẩu xác nhận không khớp.')
      return
    }

    setSavingPassword(true)

    try {
      await api.changePassword(currentPassword, newPassword)

      endSession()
    } catch (error) {
      setPasswordError(
        error instanceof Error
          ? error.message
          : 'Không thể đổi mật khẩu.',
      )
    } finally {
      setSavingPassword(false)
    }
  }

  if (loading) {
    return <div className="profile-page"><p>Đang tải hồ sơ...</p></div>
  }

  return (
    <div className="profile-page">
      <div className="profile-heading">
        <div>
          <p className="profile-eyebrow">TÀI KHOẢN CỦA BẠN</p>
          <h1>Hồ sơ cá nhân</h1>
          <p>Quản lý thông tin và bảo mật tài khoản SwiftletCare.</p>
        </div>
        <div className="profile-heading-icon">
          <UserRound size={28} />
        </div>
      </div>

      {profileError && (
        <div className="profile-message profile-message-error" role="alert">
          {profileError}
        </div>
      )}

      <div className="profile-grid">
        <section className="profile-card">
          <div className="profile-card-heading">
            <div className="profile-card-icon">
              <UserRound size={20} />
            </div>
            <div>
              <h2>Thông tin cá nhân</h2>
              <p>Cập nhật tên hiển thị và số điện thoại.</p>
            </div>
          </div>

          <div className="profile-account-summary">
            <div className="profile-avatar">
              {(fullName.trim() || account?.email || 'U')
                .charAt(0)
                .toUpperCase()}
            </div>
            <div>
              <strong>{fullName.trim() || 'Chưa cập nhật tên'}</strong>
              <span>{account?.email}</span>
              <span className="profile-role">
                {account?.role === 'ADMIN'
                  ? 'Quản trị viên'
                  : account?.role === 'OWNER'
                    ? 'Chủ sở hữu'
                    : account?.role ?? 'Người dùng'}
              </span>
            </div>
          </div>

          <form onSubmit={handleSaveProfile}>
            <label className="profile-label" htmlFor="profile-email">
              Email đăng nhập
            </label>
            <input
              id="profile-email"
              className="profile-input"
              type="email"
              value={account?.email ?? ''}
              readOnly
            />
            <p className="profile-hint">Email hiện không thể thay đổi.</p>

            <label className="profile-label" htmlFor="profile-fullname">
              Họ và tên
            </label>
            <input
              id="profile-fullname"
              className="profile-input"
              type="text"
              maxLength={150}
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              placeholder="Nhập họ và tên"
            />

            <label className="profile-label" htmlFor="profile-phone">
              Số điện thoại
            </label>
            <input
              id="profile-phone"
              className="profile-input"
              type="tel"
              maxLength={30}
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              placeholder="Nhập số điện thoại"
            />

            {profileMessage && (
              <div className="profile-message profile-message-success" role="status">
                {profileMessage}
              </div>
            )}

            <button
              className="profile-primary-button"
              type="submit"
              disabled={savingProfile}
            >
              <Save size={17} />
              {savingProfile ? 'Đang lưu...' : 'Lưu thay đổi'}
            </button>
          </form>
        </section>

        <section className="profile-card">
          <div className="profile-card-heading">
            <div className="profile-card-icon profile-security-icon">
              <ShieldCheck size={20} />
            </div>
            <div>
              <h2>Bảo mật tài khoản</h2>
              <p>Thay đổi mật khẩu để bảo vệ tài khoản.</p>
            </div>
          </div>

          <div className="profile-security-note">
            <LockKeyhole size={20} />
            <p>
              Mật khẩu mới cần có ít nhất 12 ký tự, bao gồm chữ cái và chữ số.
              Sau khi đổi mật khẩu, các phiên đăng nhập hiện tại sẽ bị thu hồi.
            </p>
          </div>

          <form onSubmit={handleChangePassword}>
            <label className="profile-label" htmlFor="profile-current-password">
              Mật khẩu hiện tại
            </label>
            <input
              id="profile-current-password"
              className="profile-input"
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
              required
            />

            <label className="profile-label" htmlFor="profile-new-password">
              Mật khẩu mới
            </label>
            <input
              id="profile-new-password"
              className="profile-input"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              minLength={12}
              required
            />

            <label className="profile-label" htmlFor="profile-confirm-password">
              Xác nhận mật khẩu mới
            </label>
            <input
              id="profile-confirm-password"
              className="profile-input"
              type="password"
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              minLength={12}
              required
            />

            {passwordError && (
              <div className="profile-message profile-message-error" role="alert">
                {passwordError}
              </div>
            )}

            {passwordMessage && (
              <div className="profile-message profile-message-success" role="status">
                {passwordMessage}
              </div>
            )}

            <button
              className="profile-primary-button"
              type="submit"
              disabled={savingPassword}
            >
              <LockKeyhole size={17} />
              {savingPassword ? 'Đang cập nhật...' : 'Đổi mật khẩu'}
            </button>
          </form>
        </section>
      </div>
    </div>
  )
}
