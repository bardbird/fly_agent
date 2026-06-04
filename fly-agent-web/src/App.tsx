import { ChatLayout } from './components/layout/ChatLayout'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { SwePipelinePage } from '@/components/swe/SwePipelinePage'
import { Tb20PipelinePage } from '@/components/tb20/Tb20PipelinePage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ChatLayout />} />
        <Route path="/swe" element={<SwePipelinePage />} />
        <Route path="/tb20" element={<Tb20PipelinePage />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
