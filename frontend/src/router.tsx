import { createBrowserRouter, Navigate } from 'react-router'
import { Layout } from './components/Layout'
import { RequireAuth } from './features/auth/RequireAuth'
import { AccountPage } from './pages/AccountPage'
import { AuthCallbackPage } from './pages/AuthCallbackPage'
import { BookingPage } from './pages/BookingPage'
import { EventDetailPage } from './pages/EventDetailPage'
import { EventListPage } from './pages/EventListPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OrganizerEventsPage } from './pages/OrganizerEventsPage'
import { RegisterPage } from './pages/RegisterPage'
import { ShowSeatsPage } from './pages/ShowSeatsPage'

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/events" replace /> },
      { path: 'events', element: <EventListPage /> },
      { path: 'events/:eventId', element: <EventDetailPage /> },
      { path: 'shows/:showId', element: <ShowSeatsPage /> },
      {
        path: 'bookings/:publicId',
        element: (
          <RequireAuth>
            <BookingPage />
          </RequireAuth>
        ),
      },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: 'auth/callback', element: <AuthCallbackPage /> },
      {
        path: 'account',
        element: (
          <RequireAuth>
            <AccountPage />
          </RequireAuth>
        ),
      },
      {
        path: 'organizer/events',
        element: (
          <RequireAuth role="ORGANIZER">
            <OrganizerEventsPage />
          </RequireAuth>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])