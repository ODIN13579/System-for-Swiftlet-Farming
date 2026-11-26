import { useState } from 'react'
import {
  LayoutDashboard,
  Thermometer,
  Bell,
  ChartNoAxesCombined,
  Bird,
  Menu,
  LogOut,
  ChevronRight,
  Wifi,
  Users,
  UserRound,
} from 'lucide-react'
import './App.css'
import Dashboard from './pages/Dashboard'
import Devices from './pages/Devices'
import Alerts from './pages/Alerts'
import Reports from './pages/Reports'
import Login from './pages/Login'
import Houses from './pages/Houses'
import Zones from './pages/Zones'
import ZoneConfig from './pages/ZoneConfig'
import AdminUsers from './pages/AdminUsers'
import Profile from './pages/Profile'
import { api, setAccessToken, type Account } from './services/api'
type Page =
  | 'dashboard'
  | 'environment'
  | 'alerts'
  | 'reports'
  | 'houses'
  | 'zones'
  | 'zone-config'
  | 'admin-users'
  | 'profile'

const menuItems = [
  { id: 'dashboard' as const, label: 'Tổng quan', icon: LayoutDashboard },
  { id: 'environment' as const, label: 'Môi trường & Thiết bị', icon: Thermometer },
  { id: 'houses' as const, label: 'Quản lý nhà yến', icon: Bird },
  { id: 'zones' as const, label: 'Quản lý khu vực', icon: LayoutDashboard },
  { id: 'zone-config' as const, label: 'Cấu hình ngưỡng', icon: Thermometer },
  { id: 'alerts' as const, label: 'Cảnh báo & AI Events', icon: Bell },
  { id: 'reports' as const, label: 'Thống kê & Báo cáo', icon: ChartNoAxesCombined },
]

function App() {
  const [activePage, setActivePage] = useState<Page>('dashboard')
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const currentPage = menuItems.find((item) => item.id === activePage)
  const [authenticated, setAuthenticated] = useState(false)
  const [currentUser, setCurrentUser] = useState<Account | null>(null)
  async function handleLogin(token: string) {
    setAccessToken(token)

    try {
      const user = await api.getMe()
      setCurrentUser(user)
      setAuthenticated(true)
    } catch (error) {
      setAccessToken(null)
      setCurrentUser(null)
      setAuthenticated(false)
      console.error('Không thể tải hồ sơ người dùng:', error)
    }
  }
  async function handleLogout() {
    try {
      await api.logout()
    } catch (error) {
      console.error('Không thể gọi API đăng xuất:', error)
    } finally {
      setAccessToken(null)
      setCurrentUser(null)
      setAuthenticated(false)
      setActivePage('dashboard')
    }
  }
  
  if (!authenticated) {
    return <Login onLogin={handleLogin} />
  }
  return (
    <div className="app-shell">
      <aside className={`sidebar ${sidebarOpen ? '' : 'sidebar-collapsed'}`}>
        <div className="brand">
          <div className="brand-icon"><Bird size={25} /></div>
          {sidebarOpen && (
            <div className="brand-text">
              <strong>SwiftletCare</strong>
            
            </div>
          )}
        </div>
        <div className="sidebar-label">{sidebarOpen ? ' ' : 'MENU'}</div>

        <nav className="sidebar-nav">
          {menuItems.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.id}
                className={`nav-item ${activePage === item.id ? 'active' : ''}`}
                onClick={() => setActivePage(item.id)}
                title={item.label}
              >
                <Icon size={20} />
                {sidebarOpen && <span>{item.label}</span>}
                {sidebarOpen && activePage === item.id && <ChevronRight size={16} />}
              </button>
            )
          })}
          {/* Hồ sơ cá nhân */}
          <button
            type="button"
            className={`nav-item ${activePage === 'profile' ? 'active' : ''}`}
            onClick={() => setActivePage('profile')}
            title="Hồ sơ cá nhân"
          >
            <UserRound size={20} />
            {sidebarOpen && <span>Hồ sơ cá nhân</span>}
            {sidebarOpen && activePage === 'profile' && <ChevronRight size={16} />}
          </button>

          {/* Chỉ ADMIN mới nhìn thấy mục quản lý tài khoản */}
          {currentUser?.role === 'ADMIN' && (
            <button
              type="button"
              className={`nav-item ${activePage === 'admin-users' ? 'active' : ''}`}
              onClick={() => setActivePage('admin-users')}
              title="Quản lý tài khoản"
            >
              <Users size={20} />
              {sidebarOpen && <span>Quản lý tài khoản</span>}
              {sidebarOpen && activePage === 'admin-users' && (
                <ChevronRight size={16} />
              )}
            </button>
          )}
                  </nav>

        <div className="sidebar-bottom">
          <div className="connection-status">
            <span className="status-dot" />
            {sidebarOpen && <span>SwiftletCare</span>}
          </div>
          <button
            className="nav-item logout-button"
            onClick={handleLogout}
          >
            <LogOut size={19} />
            {sidebarOpen && <span>Đăng xuất</span>}
          </button>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <button
            className="icon-button"
            onClick={() => setSidebarOpen(!sidebarOpen)}
            aria-label="Thu gọn hoặc mở rộng thanh bên"
          >
            <Menu size={21} />
          </button>

          <div className="breadcrumb">
            <span>SwiftletCare</span>
            <ChevronRight size={15} />
            <strong>{currentPage?.label ?? 'Tổng quan'}</strong>
          </div>

          <div className="topbar-right">
            <span className="online-label"><Wifi size={16} /> Chế độ Demo</span>
            <div className="avatar">
              {(currentUser?.fullName || currentUser?.email || 'U')
                .charAt(0)
                .toUpperCase()}
            </div>
            <div className="user-label">
              <strong>{currentUser?.fullName || currentUser?.email || 'Người dùng'}</strong>
              <span>{currentUser?.role || 'Chưa xác định vai trò'}</span>
            </div>
          </div>
        </header>

        
        <section className="page-content">
          {activePage === 'dashboard' && (
            <Dashboard onNavigateToAlerts={() => setActivePage('alerts')} />
          )}
          {activePage === 'environment' && <Devices />}
          {activePage === 'houses' && <Houses />}
          {activePage === 'zones' && <Zones />}
          {activePage === 'zone-config' && <ZoneConfig />}
          {activePage === 'alerts' && <Alerts />}
          {activePage === 'reports' && <Reports />}

          {/* Hồ sơ cá nhân */}
          {activePage === 'profile' && <Profile />}

          {/* Bảo vệ trang quản trị theo vai trò */}
          {activePage === 'admin-users' && currentUser?.role === 'ADMIN' && (
            <AdminUsers />
          )}
        </section>

      </main>
    </div>
  )
}
export default App
