import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuthStore, isInterpreter } from './auth-store'
import { AppShell } from '@/components/layout'
import LandingPage from '@/features/public/LandingPage'
import LoginPage, { OnboardingPage, MagicCallbackPage } from '@/features/auth/LoginPage'
import { StaticPage } from '@/features/public/StaticPage'
import { UserDreamsPage } from '@/features/user/DreamsPage'
import { NewDreamPage } from '@/features/user/NewDreamPage'
import { DreamDetailPage } from '@/features/user/DreamDetailPage'
import { PackagesPage, CheckoutPage, MockCheckoutPage } from '@/features/user/CheckoutPage'
import { PaymentsPage } from '@/features/user/PaymentsPage'
import { NotificationsPage } from '@/features/user/NotificationsPage'
import { ProfilePage } from '@/features/user/ProfilePage'
import { CoursesPage } from '@/features/user/CoursesPage'
import { AdminDashboard } from '@/features/admin/Dashboard'
import { AdminQueuePage } from '@/features/admin/QueuePage'
import { AdminDreamPage } from '@/features/admin/DreamPage'
import { GeoPricingPage } from '@/features/admin/GeoPricingPage'
import { PackagesAdminPage } from '@/features/admin/PackagesAdminPage'
import { WaitTimePage } from '@/features/admin/WaitTimePage'
import { UsersPage, User360Page } from '@/features/admin/UsersPage'
import { TestimonialsAdminPage } from '@/features/admin/TestimonialsAdminPage'
import { OrdersAdminPage } from '@/features/admin/OrdersAdminPage'
import { EmailSettingsPage } from '@/features/admin/EmailSettingsPage'
import { usePageTracking } from './usePageTracking'

/** Root of every route: page-view tracking lives here so each pathname change is counted once. */
function RootShell() {
  usePageTracking()
  return <Outlet />
}

function QueueAlias() {
  const { search } = useLocation()
  return <Navigate to={`/admin/dreams${search}`} replace />
}

function RequireAuth({ role }: { role?: 'USER' | 'INTERPRETER' }) {
  const user = useAuthStore((s) => s.user)
  const loc = useLocation()
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />
  if (!user.onboarded && loc.pathname !== '/onboarding') return <Navigate to="/onboarding" replace />
  if (role === 'INTERPRETER' && !isInterpreter(user)) return <Navigate to="/me" replace />
  if (role === 'USER' && isInterpreter(user)) return <Navigate to="/admin" replace />
  return <Outlet />
}

function UserLayout() {
  const { t } = useTranslation()
  return (
    <AppShell items={[
      { to: '/me', label: t('me.nav.dreams'), icon: 'moon', end: true },
      { to: '/me/new', label: t('me.nav.new'), icon: 'plus' },
      { to: '/me/packages', label: t('me.nav.packages'), icon: 'gift' },
      { to: '/me/payments', label: t('me.nav.payments'), icon: 'wallet' },
      { to: '/me/profile', label: t('me.nav.profile'), icon: 'user' },
      { to: '/me/courses', label: t('me.nav.courses'), icon: 'play' },
      { to: '/me/notifications', label: t('me.nav.notifications'), icon: 'bell' },
    ]}><Outlet /></AppShell>
  )
}

function AdminLayout() {
  const { t } = useTranslation()
  return (
    <AppShell admin items={[
      { to: '/admin', label: t('admin.nav.dashboard'), icon: 'chart', end: true },
      { to: '/admin/dreams', label: t('admin.nav.dreams'), icon: 'moon' },
      { to: '/admin/users', label: t('admin.nav.users'), icon: 'user' },
      { to: '/admin/pricing', label: t('admin.nav.pricing'), icon: 'globe' },
      { to: '/admin/packages', label: t('admin.nav.packages'), icon: 'gift' },
      { to: '/admin/wait-time', label: t('admin.nav.waitTime'), icon: 'clock' },
      { to: '/admin/testimonials', label: t('admin.nav.testimonials'), icon: 'quote' },
      { to: '/admin/orders', label: t('admin.nav.orders'), icon: 'wallet' },
      { to: '/admin/emails', label: t('admin.nav.emails'), icon: 'mail' },
      { to: '/admin/notifications', label: t('me.nav.notifications'), icon: 'bell' },
    ]}><Outlet /></AppShell>
  )
}

export const router = createBrowserRouter([{ element: <RootShell />, children: [
  { path: '/', element: <LandingPage /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/auth/callback', element: <MagicCallbackPage /> },
  { path: '/terms', element: <StaticPage kind="terms" /> },
  { path: '/privacy', element: <StaticPage kind="privacy" /> },
  { path: '/courses', element: <CoursesPage publicView /> },
  { path: '/checkout/mock/:orderId', element: <MockCheckoutPage /> },
  {
    element: <RequireAuth />, children: [{ path: '/onboarding', element: <OnboardingPage /> }],
  },
  {
    element: <RequireAuth role="USER" />,
    children: [{
      path: '/me', element: <UserLayout />, children: [
        { index: true, element: <UserDreamsPage /> },
        { path: 'new', element: <NewDreamPage /> },
        { path: 'dreams/:id', element: <DreamDetailPage /> },
        { path: 'dreams/:id/edit', element: <NewDreamPage /> },
        { path: 'packages', element: <PackagesPage /> },
        { path: 'checkout', element: <CheckoutPage /> },
        { path: 'payments', element: <PaymentsPage /> },
        { path: 'notifications', element: <NotificationsPage /> },
        { path: 'profile', element: <ProfilePage /> },
        { path: 'courses', element: <CoursesPage /> },
      ],
    }],
  },
  {
    element: <RequireAuth role="INTERPRETER" />,
    children: [{
      path: '/admin', element: <AdminLayout />, children: [
        { index: true, element: <AdminDashboard /> },
        { path: 'dreams', element: <AdminQueuePage /> },
        { path: 'dreams/:id', element: <AdminDreamPage /> },
        { path: 'pricing', element: <GeoPricingPage /> },
        { path: 'packages', element: <PackagesAdminPage /> },
        { path: 'wait-time', element: <WaitTimePage /> },
        { path: 'users', element: <UsersPage /> },
        { path: 'users/:id', element: <User360Page /> },
        { path: 'testimonials', element: <TestimonialsAdminPage /> },
        { path: 'orders', element: <OrdersAdminPage /> },
        { path: 'emails', element: <EmailSettingsPage /> },
        { path: 'notifications', element: <NotificationsPage /> },
        // server-side insight links point at /admin/queue — the queue lives at /admin/dreams
        { path: 'queue', element: <QueueAlias /> },
      ],
    }],
  },
  { path: '*', element: <Navigate to="/" replace /> },
] }])
