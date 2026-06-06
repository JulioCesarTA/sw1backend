from pydantic import BaseModel
from typing import Optional, List

class ReportFilters(BaseModel):
    departmentName: Optional[str] = None
    userName: Optional[str] = None
    status: Optional[str] = None
    dateFrom: Optional[str] = None
    dateTo: Optional[str] = None

class ReportSpec(BaseModel):w
    intent: str
    filters: ReportFilters
    columns: List[str]
    orderBy: str = "createdAt"
    orderDir: str = "desc"
    title: str
    format: str = "screen"   # screen | word | excel

class AnalyzeRequest(BaseModel):
    text: str
    format: str = "screen"

class DownloadRequest(BaseModel):
    spec: ReportSpec
    format: str  # word | excel
