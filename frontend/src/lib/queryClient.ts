import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000, // catalog data changes rarely; avoid refetching on every mount
      refetchOnWindowFocus: false,
      // Retry network/5xx errors once; never retry 4xx (the same request would fail the same way).
      retry: (failureCount, error) => !(error instanceof ApiError && error.isClientError) && failureCount < 1,
    },
  },
})
